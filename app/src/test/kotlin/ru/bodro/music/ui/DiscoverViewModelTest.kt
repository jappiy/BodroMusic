package ru.bodro.music.ui

import android.content.Context
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.bodro.music.core.downloads.DiscoverArtist
import ru.bodro.music.core.downloads.DiscoverIntent
import ru.bodro.music.core.downloads.DiscoverResult
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.downloads.RequestKind
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.screens.search.DiscoverEvent
import ru.bodro.music.ui.screens.search.DiscoverViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val context = mockk<Context>(relaxed = true)
    private val library = mockk<LibraryRepository>(relaxed = true)
    private val track = DiscoverTrack("Город Сочи", "Трофим", durationMs = 200_000, source = "yandex", sourceId = "1")
    private val trofim = DiscoverArtist("Трофим", null, "yandex", "77")
    private val other = DiscoverArtist("Сочи", null, "yandex", "5")
    private val requests = MutableStateFlow<List<DownloadRequest>>(emptyList())
    private val downloads = mockk<DownloadsRepository>(relaxed = true) {
        every { serviceUrl } returns MutableStateFlow("http://nas:8790/")
        every { requests } returns this@DiscoverViewModelTest.requests
        every { observe() } returns flowOf(emptyList())
    }

    private fun vm() = DiscoverViewModel(context, downloads, library)

    @Test
    fun `search shows catalog tracks and download queues request`() = runTest(main.dispatcher) {
        val result = DiscoverResult(DiscoverIntent.Track, "yandex", tracks = listOf(track))
        coEvery { downloads.discover("город сочи") } returns result
        coEvery { downloads.downloadTrack(track) } returns
            DownloadRequest("r1", RequestKind.Track, "Трофим", "Город Сочи", status = RequestStatus.Queued)
        val vm = vm()
        vm.search("город сочи")
        advanceUntilIdle()
        assertEquals(LoadState.Content(result), vm.state.value?.result)

        vm.events.test {
            vm.download(track)
            assertTrue(awaitItem() is DiscoverEvent.Message)
        }
        coVerify(exactly = 1) { downloads.downloadTrack(track) }
    }

    @Test
    fun `same query is not searched twice, retry after error searches again`() = runTest(main.dispatcher) {
        coEvery { downloads.discover(any()) } throws DownloadsException(DownloadsError.Network("timeout"))
        val vm = vm()
        vm.search("трофим")
        advanceUntilIdle()
        assertTrue(vm.state.value?.result is LoadState.Error)
        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 2) { downloads.discover("трофим") }

        vm.reset()
        assertNull(vm.state.value)
    }

    @Test
    fun `selecting another artist loads its top tracks and toggles back`() = runTest(main.dispatcher) {
        coEvery { downloads.discover("трофим") } returns
            DiscoverResult(DiscoverIntent.Artist, "yandex", artists = listOf(trofim, other), artist = trofim, topTracks = listOf(track))
        coEvery { downloads.artistTopTracks(other) } returns emptyList()
        val vm = vm()
        vm.search("трофим")
        advanceUntilIdle()

        vm.selectArtist(other)
        advanceUntilIdle()
        assertEquals(other, vm.state.value?.selected)
        assertEquals(LoadState.Content(emptyList<DiscoverTrack>()), vm.state.value?.selectedTracks)

        vm.selectArtist(other)
        assertNull(vm.state.value?.selected)
    }
}
