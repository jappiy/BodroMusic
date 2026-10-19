package ru.bodro.music.core.domain.wave

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.Song

class PoolMixerTest {
    private val base = library.take(20)
    private val pool = (0 until 30).map { n ->
        Song(id = "p-$n", title = "New $n", artist = "Newcomer ${n % 4}", artistId = "nw-${n % 4}", duration = 200)
    }
    private val poolIds = pool.map { it.id }.toSet()

    @Test
    fun `share of pool tracks matches character`() {
        for ((character, expected) in listOf(WaveCharacter.Favorites to 0, WaveCharacter.Mix to 5, WaveCharacter.Discover to 10)) {
            val mixed = PoolMixer.mix(base, pool, character.poolShare, Random(1))
            assertEquals(20, mixed.size)
            assertEquals("$character", expected, mixed.count { it.id in poolIds })
            assertEquals(mixed.size, mixed.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `no two pool tracks of one artist in a row`() {
        repeat(200) { seed ->
            val recent = listOf(pool[0])
            val mixed = PoolMixer.mix(base, pool, 0.5, Random(seed), recent = recent, poolIds = poolIds)
            val poolSequence = (recent + mixed).filter { it.id in poolIds }.map { it.artistId }
            assertTrue("seed $seed: $poolSequence", poolSequence.zipWithNext().none { (a, b) -> a == b })
        }
    }

    @Test
    fun `pool tracks are spread over the batch`() {
        val mixed = PoolMixer.mix(base, pool, 0.25, Random(7))
        val positions = mixed.withIndex().filter { it.value.id in poolIds }.map { it.index }
        assertTrue("$positions", positions.zipWithNext().all { (a, b) -> b - a >= 2 })
    }

    @Test
    fun `favorites removes pool tracks that came from the engine`() {
        val withPool = base.take(19) + pool[0]
        val mixed = PoolMixer.mix(withPool, pool, 0.0, Random(1), poolIds = poolIds)
        assertTrue(mixed.none { it.id in poolIds })
    }

    @Test
    fun `empty pool leaves the batch as is`() {
        assertEquals(base, PoolMixer.mix(base, emptyList(), 0.5, Random(1)))
    }
}
