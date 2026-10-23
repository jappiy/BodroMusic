package ru.bodro.music.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.ui.screens.player.playerLayout

/**
 * Раскладка полноэкранного плеера (задание 021). Размеры — область плеера без системных панелей и полей 24 dp:
 * обычный телефон, его ландшафт, складной телефон в сложенном и разложенном виде.
 */
class PlayerLayoutTest {
    /** Высота блока под обложкой: шапка, название, перемотка, кнопки, нижняя панель с отступами. */
    private val controlsHeight = 56.dp + 300.dp

    private fun fits(width: Dp, height: Dp): Boolean {
        val layout = playerLayout(width, height)
        return if (layout.twoPane) layout.coverSize + 56.dp <= height else layout.coverSize + controlsHeight <= height
    }

    @Test
    fun `phone portrait - cover by width, one column`() {
        val layout = playerLayout(312.dp, 740.dp)
        assertFalse(layout.twoPane)
        assertEquals(312.dp, layout.coverSize)
        assertTrue(fits(312.dp, 740.dp))
    }

    @Test
    fun `phone landscape - two columns, cover by height`() {
        val layout = playerLayout(760.dp, 340.dp)
        assertTrue(layout.twoPane)
        assertTrue(layout.coverSize <= 340.dp - 56.dp)
        assertTrue(fits(760.dp, 340.dp))
    }

    @Test
    fun `unfolded near-square portrait - cover limited so controls fit`() {
        // Разложенный складной в портрете: ~ 840 × 790 dp, без полей и панелей ~ 790 × 720.
        val layout = playerLayout(790.dp, 720.dp)
        assertTrue(layout.twoPane) // шире, чем выше, — две колонки
        val portrait = playerLayout(700.dp, 760.dp)
        assertFalse(portrait.twoPane)
        assertTrue(portrait.coverSize < 700.dp)
        assertTrue(fits(700.dp, 760.dp))
    }

    @Test
    fun `cover is never larger than the cap and never tiny`() {
        assertEquals(560.dp, playerLayout(1200.dp, 2000.dp).coverSize)
        assertEquals(120.dp, playerLayout(200.dp, 150.dp).coverSize)
    }
}
