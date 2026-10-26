package ru.bodro.music.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.ui.screens.player.PlayerPalette
import ru.bodro.music.ui.theme.Contrast

class ContrastTest {
    private val covers = listOf(
        Color(0xFF1B1B2F), // тёмная обложка
        Color(0xFFE8E2D0), // светлая (светлая тема, тон обложки осветлён)
        Color(0xFF7A7A7A), // средне-серая
        Color(0xFFF2EFEA), // фон светлой темы без обложки (surfaceContainerHigh)
        Color(0xFF2B2930), // фон тёмной темы без обложки
        Color(0xFFB0413E), // насыщенный красный
    )

    @Test
    fun `wcag ratio of black on white is 21`() {
        assertEquals(21f, Contrast.ratio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, Contrast.ratio(Color.Gray, Color.Gray), 0.001f)
    }

    @Test
    fun `pick keeps preferred colour when it is readable everywhere`() {
        val dark = listOf(Color(0xFF101010), Color(0xFF202020))
        assertEquals(Color.White, Contrast.pick(Color.White, dark, Contrast.TEXT))
        // Тёмный «предпочтительный» на тёмном фоне — заменяется светлым.
        assertEquals(Contrast.Light, Contrast.pick(Color(0xFF303030), dark, Contrast.TEXT))
    }

    @Test
    fun `player icons and text stay readable over the whole gradient`() {
        for (top in covers) {
            // Предпочтительный цвет — тёмный (так раньше выходило на светлом верху), низ градиента — тёмный.
            val palette = PlayerPalette.over(top, preferredContent = Color(0xFF15130F), accent = Color(0xFF6A4C00))
            val surfaces = Contrast.gradient(palette.background, palette.bottom)
            assertTrue("текст на $top", Contrast.worst(palette.content, surfaces) >= Contrast.TEXT)
            assertTrue("подписи на $top", Contrast.worst(palette.secondary, surfaces) >= Contrast.TEXT)
            assertTrue("иконки на $top", Contrast.worst(palette.muted, surfaces) >= Contrast.ICON)
            assertTrue("акцент-иконки на $top", Contrast.worst(palette.accentIcon, surfaces) >= Contrast.ICON)
            assertTrue("акцент-текст на $top", Contrast.worst(palette.accentText, surfaces) >= Contrast.TEXT)
            assertTrue("иконка на акценте", Contrast.ratio(palette.onAccent, palette.accent) >= Contrast.ICON)
        }
    }
}
