package ru.bodro.music.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.bodro.music.R
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.SearchResult
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.NetworkErrorKind
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.core.subsonic.SubsonicError
import ru.bodro.music.core.subsonic.SubsonicException
import ru.bodro.music.data.account.AccountRepository
import ru.bodro.music.data.repository.HomeData
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.PlaylistRepository
import ru.bodro.music.data.repository.SearchHistoryRepository
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.screens.charts.ChartsTabViewModel
import ru.bodro.music.ui.screens.login.LoginViewModel
import ru.bodro.music.ui.screens.search.SearchViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTests {
    @get:Rule val main = MainDispatcherRule()

    // region Поиск

    private val library = mockk<LibraryRepository>(relaxed = true)
    private val playlists = mockk<PlaylistRepository> { coEvery { playlists() } returns emptyList() }
    private val history = mockk<SearchHistoryRepository>(relaxed = true) { every { observe(any()) } returns flowOf(listOf("кино")) }

    @Test
    fun `search is debounced by 300 ms and only the last query hits the server`() = runTest(main.dispatcher) {
        coEvery { library.search(any()) } answers { SearchResult(songs = listOf(Song("1", firstArg()))) }
        val vm = SearchViewModel(library, playlists, history)
        vm.results.test {
            assertNull(awaitItem())
            vm.setQuery("к")
            vm.setQuery("ки")
            vm.setQuery("кин")
            vm.setQuery("кино")
            advanceTimeBy(299)
            expectNoEvents()
            advanceTimeBy(2)
            assertTrue(awaitItem() is LoadState.Loading)
            val content = awaitItem() as LoadState.Content
            assertEquals("кино", content.data.result.songs.single().title)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { library.search(any()) }
        coVerify(exactly = 1) { library.search("кино") }
    }

    @Test
    fun `short query shows history instead of results`() = runTest(main.dispatcher) {
        val vm = SearchViewModel(library, playlists, history)
        vm.results.test {
            assertNull(awaitItem())
            vm.setQuery("к")
            advanceTimeBy(400)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { library.search(any()) }
    }

    @Test
    fun `search error is mapped to offline ui error`() = runTest(main.dispatcher) {
        coEvery { library.search(any()) } throws SubsonicException(SubsonicError.Network(NetworkErrorKind.Unreachable, null))
        val vm = SearchViewModel(library, playlists, history)
        vm.results.test {
            awaitItem()
            vm.setQuery("кино")
            advanceTimeBy(301)
            assertTrue(awaitItem() is LoadState.Loading)
            val error = awaitItem() as LoadState.Error
            assertEquals(R.string.error_unreachable, error.error.message)
            assertTrue(error.error.isOffline)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Вкладка «Чарты» (подборки, бывшие на главной)

    @Test
    fun `charts tab loads content and maps errors`() = runTest(main.dispatcher) {
        coEvery { library.home() } throws SubsonicException(SubsonicError.Api(40, "Wrong"))
        val charts = mockk<ru.bodro.music.data.reco.ChartsRepository> { coEvery { home() } returns emptyList() }
        val vm = ChartsTabViewModel(library, charts)
        advanceUntilIdle()
        assertEquals(R.string.error_wrong_credentials, (vm.state.value as LoadState.Error).error.message)

        val data = HomeData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        coEvery { library.home() } returns data
        vm.load()
        advanceUntilIdle()
        assertEquals(data, (vm.state.value as LoadState.Content).data)
    }

    // endregion

    // region Вход

    @Test
    fun `login validates input without network`() = runTest(main.dispatcher) {
        val vm = LoginViewModel(mockk(), mockk())
        vm.login("", "", "u", "p")
        assertEquals(R.string.login_error_no_address, vm.state.value.error?.message)
        vm.login("", "http://music.example.org", "u", "p")
        assertEquals(R.string.error_cleartext, vm.state.value.error?.message)
        vm.login("192.168.1.10:4533", "", "", "p")
        assertEquals(R.string.login_error_credentials_empty, vm.state.value.error?.message)
    }

    @Test
    fun `login falls back to external address and saves account`() = runTest(main.dispatcher) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                okJson("""{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,"serverVersion":"0.58"}}"""),
            )
            server.enqueue(
                okJson("""{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,"openSubsonicExtensions":[]}}"""),
            )
            val accounts = mockk<AccountRepository>()
            coEvery { accounts.addOrUpdate(any(), any(), any(), any(), any()) } returns Account(1, "vasya", null, null)
            val client = SubsonicClient(OkHttpClient(), { null }, dispatcher = main.dispatcher)
            val vm = LoginViewModel(client, accounts)
            // Локальный адрес не отвечает (закрытый порт), внешний — MockWebServer (loopback считается домашней сетью).
            vm.login("http://127.0.0.1:1", server.url("/").toString(), " vasya ", "pw")
            advanceUntilIdle()
            assertTrue(vm.state.value.error?.toString(), vm.state.value.done)
            coVerify { accounts.addOrUpdate("vasya", "pw", "http://127.0.0.1:1/", server.url("/").toString(), any()) }
        }
    }

    @Test
    fun `login stops on wrong credentials`() = runTest(main.dispatcher) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(okJson("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong"}}}"""))
            val client = SubsonicClient(OkHttpClient(), { null }, dispatcher = main.dispatcher)
            val vm = LoginViewModel(client, mockk())
            vm.login(server.url("/").toString(), "https://never.example.org", "vasya", "bad")
            advanceUntilIdle()
            assertEquals(R.string.error_wrong_credentials, vm.state.value.error?.message)
            assertEquals("external address not tried after auth error", 1, server.requestCount)
        }
    }

    private fun okJson(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    // endregion
}
