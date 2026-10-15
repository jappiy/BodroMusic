package ru.bodro.music.core.domain.wave

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSettings

class WaveEngineTest {
    private val candidates = disjointCandidates()
    private val engine = WaveEngine(emptyList())

    private fun assertArtistRuns(songs: List<Song>, tail: List<Song> = emptyList()) {
        val all = tail.takeLast(2) + songs
        for (i in 2 until all.size) {
            val a = WaveEngine.artistKey(all[i - 2])
            assertFalse(
                "3 tracks of $a in a row: ${all.map { it.artistId }}",
                a == WaveEngine.artistKey(all[i - 1]) && a == WaveEngine.artistKey(all[i]),
            )
        }
    }

    @Test
    fun `disliked tracks never appear - property over 1000 seeds`() {
        repeat(1000) { seed ->
            val random = Random(seed)
            val disliked = library.shuffled(random).take(random.nextInt(1, 800)).map { it.id }.toSet()
            val result = engine.select(candidates, request(WaveProfile(dislikedSongIds = disliked), seed = seed))
            val leaked = result.filter { it.id in disliked }
            assertTrue("seed $seed leaked $leaked", leaked.isEmpty())
            assertEquals("seed $seed batch", 20, result.size)
            assertArtistRuns(result)
            assertEquals("no duplicates", result.size, result.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `server side dislike (rating 1) is also excluded`() {
        val disliked = candidates.map { it.copy(song = it.song.copy(userRating = 1)) }
        assertTrue(engine.select(disliked, request()).isEmpty())
    }

    @Test
    fun `no more than two tracks of one artist in a row, considering the queue tail`() {
        // Почти все кандидаты — один исполнитель.
        val mono = library.filter { it.artistId == "ar-1" }.map { WaveCandidate(it, WaveBucket.Liked) } +
            library.filter { it.artistId == "ar-2" }.take(3).map { WaveCandidate(it, WaveBucket.Liked) }
        val tail = library.filter { it.artistId == "ar-1" }.takeLast(2).let { listOf(it[0].copy(id = "t1"), it[1].copy(id = "t2")) }
        repeat(200) { seed ->
            val result = engine.select(mono, request(recent = tail, seed = seed))
            assertArtistRuns(result, tail)
            assertTrue(result.isNotEmpty())
            // 3 трека ar-2 разбивают серии: максимум 3 + 3 * 2 = 9, остальное отбрасывается, а не нарушает правило.
            assertTrue("impossible tracks are dropped, not violated", result.size <= 9)
        }
    }

    @Test
    fun `anti repeat - 7 days, liked tracks 2 days`() {
        val target = library[0]
        val likedTarget = library[1]
        fun played(daysAgo: Double) = NOW - (daysAgo * DAY).toLong()

        val pool = listOf(WaveCandidate(target, WaveBucket.Familiar), WaveCandidate(likedTarget, WaveBucket.Liked))
        fun pick(targetDays: Double, likedDays: Double) = engine.select(
            pool,
            request(
                WaveProfile(
                    likedSongIds = setOf(likedTarget.id),
                    lastPlayedAt = mapOf(target.id to played(targetDays), likedTarget.id to played(likedDays)),
                ),
            ),
        ).map { it.id }.toSet()

        assertEquals(emptySet<String>(), pick(6.9, 1.9))
        assertEquals(setOf(likedTarget.id), pick(6.9, 2.1))
        assertEquals(setOf(target.id, likedTarget.id), pick(7.1, 2.1))
    }

    @Test
    fun `tracks already in the queue are not repeated`() {
        val recent = library.take(500)
        repeat(50) { seed ->
            val result = engine.select(candidates, request(recent = recent, seed = seed))
            assertTrue(result.none { song -> recent.any { it.id == song.id } })
        }
    }

    @Test
    fun `bucket shares match the spec with tolerance`() {
        val counts = IntArray(WaveBucket.entries.size)
        val bucketOf = candidates.associate { it.song.id to it.bucket }
        repeat(300) { seed ->
            engine.select(candidates, request(seed = seed)).forEach { counts[bucketOf.getValue(it.id).ordinal]++ }
        }
        val total = counts.sum().toDouble()
        val expected = WaveShares.DEFAULT
        WaveBucket.entries.forEach { bucket ->
            val actual = counts[bucket.ordinal] / total
            assertTrue("$bucket: $actual vs ${expected.of(bucket)}", abs(actual - expected.of(bucket)) < 0.03)
        }
    }

    @Test
    fun `allocation is exact`() {
        val allocation = engine.allocate(20, WaveShares.DEFAULT)
        assertEquals(20, allocation.values.sum())
        assertEquals(5, allocation[WaveBucket.Liked])
        assertEquals(4, allocation[WaveBucket.Familiar])
        assertEquals(7, allocation[WaveBucket.Neighbors])
        assertEquals(4, allocation[WaveBucket.Discovery])
    }

    @Test
    fun `missing buckets are filled from others`() {
        val onlyLiked = candidates.filter { it.bucket == WaveBucket.Liked }
        assertEquals(20, engine.select(onlyLiked, request()).size)
    }

    @Test
    fun `deterministic for the same seed`() {
        val a = engine.select(candidates, request(seed = 42))
        val b = engine.select(candidates, request(seed = 42))
        val c = engine.select(candidates, request(seed = 43))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `artist with 3 dislikes and no likes is 5x less likely`() {
        val artistA = library.filter { it.artistId == "ar-1" }.map { WaveCandidate(it, WaveBucket.Neighbors) }
        val artistB = library.filter { it.artistId == "ar-2" }.map { WaveCandidate(it, WaveBucket.Neighbors) }
        val profile = WaveProfile(artistDislikes = mapOf("ar-1" to 3))
        val w = engine.adjustedWeight(artistA.first(), profile)
        assertEquals(0.2, w, 1e-9)
        assertEquals(1.0, engine.adjustedWeight(artistA.first(), profile.copy(artistLikes = mapOf("ar-1" to 1))), 1e-9)
        var countA = 0
        var countB = 0
        // batch = 1, чтобы правило «не больше 2 подряд» не перемешивало исполнителей.
        repeat(1200) { seed ->
            engine.select(artistA + artistB, request(profile, seed = seed, batch = 1)).forEach {
                if (it.artistId == "ar-1") countA++ else countB++
            }
        }
        // Ожидаемо A ≈ 1/6 выборок.
        assertTrue("A=$countA B=$countB", countA * 3 < countB && countA * 8 > countB)
    }

    @Test
    fun `early skips lower weight of artist and genre`() {
        val candidate = WaveCandidate(library[0], WaveBucket.Neighbors)
        val profile = WaveProfile(artistSkips = mapOf("ar-0" to 2), genreSkips = mapOf("Rock" to 1))
        val weight = engine.adjustedWeight(candidate, profile)
        assertTrue(weight < 1.0)
        assertTrue(weight >= WaveEngine.MIN_SKIP_FACTOR * WaveEngine.MIN_SKIP_FACTOR)
    }

    @Test
    fun `genre and decade filters`() {
        val settings = WaveSettings(genre = "Jazz", decade = 1990)
        repeat(20) { seed ->
            val result = engine.select(candidates, request(settings = settings, seed = seed))
            assertTrue(result.isNotEmpty())
            assertTrue(result.all { it.genre == "Jazz" && it.year in 1990..1999 })
        }
    }

    @Test
    fun `favorites mode has no discovery`() {
        val bucketOf = candidates.associate { it.song.id to it.bucket }
        repeat(50) { seed ->
            val result = engine.select(candidates, request(settings = WaveSettings(mode = WaveMode.Favorites), seed = seed))
            assertTrue(result.none { bucketOf[it.id] == WaveBucket.Discovery })
        }
    }

    @Test
    fun `discovery share from settings`() {
        val shares = WaveShares.forSettings(WaveSettings(), discoveryShare = 0.5)
        assertEquals(0.5, shares.discovery, 1e-9)
        assertEquals(1.0, shares.liked + shares.familiar + shares.neighbors + shares.discovery, 1e-9)
    }

    @Test
    fun `engine survives failing source`() = runTest {
        val failing = object : WaveSource {
            override val name = "broken"
            override suspend fun candidates(request: WaveRequest): List<WaveCandidate> = error("boom")
        }
        val ok = object : WaveSource {
            override val name = "ok"
            override suspend fun candidates(request: WaveRequest) = candidates
        }
        assertEquals(20, WaveEngine(listOf(failing, ok)).nextBatch(request()).size)
    }
}
