package ru.bodro.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * Контраст по WCAG 2.x для цветов поверх динамического фона (палитра обложки, градиенты).
 *
 * Цвет проверяется не по одной точке фона, а по всем образцам фона, над которыми элемент может оказаться
 * (например, верх и низ градиента): контраст считается по худшему. Не хватает — белый или чёрный, у кого худший
 * контраст лучше. Полупрозрачный цвет сначала накладывается на фон.
 */
object Contrast {
    /** Обычный текст (WCAG 1.4.3). */
    const val TEXT = 4.5f

    /** Иконки и крупные графические элементы (WCAG 1.4.11). */
    const val ICON = 3f

    val Light = Color(0xFFF7F3EC)
    val Dark = Color(0xFF15130F)

    fun ratio(foreground: Color, background: Color): Float {
        val bg = background.copy(alpha = 1f)
        val fg = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
        val a = fg.luminance() + 0.05f
        val b = bg.luminance() + 0.05f
        return if (a > b) a / b else b / a
    }

    /** Худший контраст цвета по всем образцам фона. */
    fun worst(foreground: Color, backgrounds: List<Color>): Float = backgrounds.minOf { ratio(foreground, it) }

    /** [preferred], если по всем образцам фона контраст не ниже [min], иначе светлый или тёмный фолбэк. */
    fun pick(preferred: Color, backgrounds: List<Color>, min: Float): Color = when {
        worst(preferred, backgrounds) >= min -> preferred
        else -> listOf(Light, Dark).maxBy { worst(it, backgrounds) }
    }

    /**
     * Приглушённый вариант [color] (подписи, неактивные иконки): прозрачность [alpha], но не ниже той, при которой
     * контраст по всем образцам ещё не меньше [min]. Не хватает и непрозрачного — непрозрачный.
     */
    fun muted(color: Color, backgrounds: List<Color>, min: Float, alpha: Float): Color {
        var a = alpha
        while (a < 1f) {
            val candidate = color.copy(alpha = a)
            if (worst(candidate, backgrounds) >= min) return candidate
            a += ALPHA_STEP
        }
        return color.copy(alpha = 1f)
    }

    /** Образцы вертикального градиента [top] → [bottom] в долях [from]..[to] высоты (включая края). */
    fun gradient(top: Color, bottom: Color, from: Float = 0f, to: Float = 1f, steps: Int = 5): List<Color> =
        (0..steps).map { i -> lerpColor(top, bottom, from + (to - from) * i / steps) }

    private const val ALPHA_STEP = 0.05f
}
