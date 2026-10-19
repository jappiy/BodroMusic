package ru.bodro.music.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.data.reco.ChartsRepository

class ChartsOrderTest {
    private fun charts(vararg ids: String) = ids.map { Chart(id = it, title = it) }

    @Test
    fun `saved order first, new charts at the end in server order`() {
        val arranged = ChartsRepository.arrange(charts("a", "b", "c", "d"), listOf("c", "a"))
        assertEquals(listOf("c", "a", "b", "d"), arranged.map { it.id })
    }

    @Test
    fun `no saved order keeps server order`() {
        assertEquals(listOf("a", "b"), ChartsRepository.arrange(charts("a", "b"), emptyList()).map { it.id })
    }
}
