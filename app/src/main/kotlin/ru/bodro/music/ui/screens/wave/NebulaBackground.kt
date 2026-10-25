package ru.bodro.music.ui.screens.wave

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import ru.bodro.music.data.settings.BackgroundAnimation
import ru.bodro.music.playback.AudioSpectrum
import ru.bodro.music.playback.SpectrumFrame

/**
 * Фон главной — «туманность» (ориентир — «Моя волна» Яндекс Музыки): плотный дымный слой в цвете обложки
 * с тонкими светлыми прожилками, заметно течёт, слегка пульсирует в ритм; верхние ~45 % экрана — туманность,
 * ниже — плавное затухание в чёрный под кнопками.
 *
 * - Android 13+ — AGSL ([RuntimeShader], fBm с двойным искажением координат). Рисуется в слой половинного
 *   разрешения и растягивается с билинейной фильтрацией. Раньше Android 13 и на программном холсте — градиенты.
 * - Палитра ([nebulaPalette]) — от доминантного цвета обложки; смена трека — переход за [PALETTE_MS] мс.
 * - Ритм — из PCM плеера ([AudioSpectrum], без микрофона): энергия с AGC и доли ([NebulaRhythm]); кадр берётся
 *   с задержкой вывода звука, поэтому совпадает с тем, что слышно (и в Bluetooth-наушниках).
 * - [mode]: Полный — каждый кадр; Экономный (и системное энергосбережение) — 30 кадров/с без пульсации;
 *   Выкл и системное «Отключить анимацию» — неподвижный кадр. Не на экране / приложение свёрнуто — кадров нет.
 */
@Composable
fun NebulaBackground(colors: List<Color>, mode: BackgroundAnimation, playing: Boolean, modifier: Modifier = Modifier) {
    val palette = remember(colors) { nebulaPalette(colors.firstOrNull() ?: Color(0xFF7A3FD0)) }
    val colA by animateColorAsState(palette[0], tween(PALETTE_MS), label = "nebula-a")
    val colB by animateColorAsState(palette[1], tween(PALETTE_MS), label = "nebula-b")
    val colC by animateColorAsState(palette[2], tween(PALETTE_MS), label = "nebula-c")

    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Системная «Отключить анимацию» (и автотесты) — неподвижный кадр, как у остальных анимаций Compose.
    val systemAnimations = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val animate = mode != BackgroundAnimation.Off && systemAnimations
    val running = animate && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (animate && playing) {
        LifecycleResumeEffect(Unit) {
            AudioSpectrum.acquire()
            onPauseOrDispose { AudioSpectrum.release() }
        }
    }

    var phase by remember { mutableFloatStateOf(START_PHASE) }
    var energy by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    val rhythm = remember { NebulaRhythm() }
    LaunchedEffect(running, playing, mode) {
        if (!running) return@LaunchedEffect
        var last = 0L
        var pending = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MAX_DT)
                last = now
                pending += dt
                val eco = mode == BackgroundAnimation.Eco || context.powerSaving()
                if (eco && pending < ECO_FRAME_S) return@withFrameNanos
                val step = pending
                pending = 0f
                rhythm.update(if (playing) AudioSpectrum.frameAt() else SpectrumFrame.Silent, step, playing, pulse = !eco)
                energy = rhythm.energy
                beat = rhythm.beat
                phase = rhythm.phase
            }
        }
    }

    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) nebulaShader() else null }
    Box(modifier) {
        Canvas(
            Modifier
                // Половинное разрешение: рисуем в слой вдвое меньше и растягиваем (шейдер мягкий, деталей не теряет).
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (w + 1) / 2, maxHeight = (h + 1) / 2))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    scaleX = 2f
                    scaleY = 2f
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            drawRect(Color.Black)
            val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            if (shader != null && hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawNebula(shader, colA, colB, colC, phase, energy, beat)
            } else {
                drawGradientNebula(colA, colB, colC, phase, energy, beat)
            }
        }
    }
}

private fun Context.powerSaving(): Boolean = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

/**
 * Ритм для фона из кадров звука: [energy] — громкость (подъём ~50 мс, спад ~400 мс), [beat] — 1 на долю
 * с затуханием τ ≈ 180 мс, [phase] — фаза течения: скорость 0,8 + 1,2·энергия при воспроизведении, 0,25 на паузе,
 * смена скорости плавная.
 */
internal class NebulaRhythm {
    var energy = 0f
        private set
    var beat = 0f
        private set
    var phase = START_PHASE
        private set
    private var speed = PAUSE_SPEED
    private var lastBeats = -1

    fun update(frame: SpectrumFrame, dt: Float, playing: Boolean, pulse: Boolean = true) {
        if (dt <= 0f) return
        val target = if (playing) frame.energy else 0f
        energy += (target - energy) * smoothing(dt, if (target > energy) ATTACK_S else RELEASE_S)
        beat *= exp(-dt / BEAT_TAU_S)
        if (lastBeats >= 0 && frame.beats > lastBeats && playing && pulse) beat = 1f
        if (frame.beats != 0 || lastBeats < 0) lastBeats = frame.beats
        val targetSpeed = if (playing) PLAY_SPEED + ENERGY_SPEED * energy else PAUSE_SPEED
        speed += (targetSpeed - speed) * smoothing(dt, SPEED_TAU_S)
        phase += dt * speed
    }

    private fun smoothing(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    private companion object {
        const val ATTACK_S = 0.05f
        const val RELEASE_S = 0.4f
        const val BEAT_TAU_S = 0.18f
        const val SPEED_TAU_S = 0.6f
        const val PLAY_SPEED = 0.8f
        const val ENERGY_SPEED = 1.2f
        const val PAUSE_SPEED = 0.25f
    }
}

/**
 * Палитра туманности от доминантного цвета обложки: B — тот же оттенок, насыщеннее, яркость ~0,8; A = B × 0,45
 * (тёмный дым); C = B, смешанный с белым на 60 % (прожилки). Почти серые обложки — мягкий, а не кислотный цвет.
 */
internal fun nebulaPalette(dominant: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(dominant.toArgb(), hsv)
    hsv[1] = if (hsv[1] < 0.12f) 0.35f else (hsv[1] * 1.25f).coerceIn(0.55f, 0.95f)
    hsv[2] = 0.8f
    val b = Color(android.graphics.Color.HSVToColor(hsv))
    val a = Color(b.red * 0.45f, b.green * 0.45f, b.blue * 0.45f)
    val c = lerp(b, Color.White, 0.6f)
    return listOf(a, b, c)
}

// region Android 13+: AGSL

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun nebulaShader(): RuntimeShader? = runCatching { RuntimeShader(NEBULA_AGSL) }.getOrNull()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawNebula(shader: RuntimeShader, a: Color, b: Color, c: Color, phase: Float, energy: Float, beat: Float) {
    shader.setFloatUniform("uRes", size.width, size.height)
    shader.setFloatUniform("uTime", phase)
    shader.setFloatUniform("uEnergy", energy)
    shader.setFloatUniform("uBeat", beat)
    shader.setFloatUniform("uColA", a.red, a.green, a.blue)
    shader.setFloatUniform("uColB", b.red, b.green, b.blue)
    shader.setFloatUniform("uColC", c.red, c.green, c.blue)
    shader.setFloatUniform("uFadeStart", FADE_START)
    shader.setFloatUniform("uFadeLen", FADE_LEN)
    drawRect(ShaderBrush(shader))
}

/** Шейдер из задания 2026-10-25 (прототип `docs/assets/2026-10-25/nebula_prototype.png`). */
private const val NEBULA_AGSL = """
uniform float2 uRes;
uniform float  uTime;
uniform float  uEnergy;
uniform float  uBeat;
uniform float3 uColA;
uniform float3 uColB;
uniform float3 uColC;
uniform float  uFadeStart;
uniform float  uFadeLen;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(float2 p) {
    float2 i = floor(p); float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + float2(1,0)), c = hash(i + float2(0,1)), d = hash(i + float2(1,1));
    return a + (b - a) * u.x + (c - a) * u.y + (a - b - c + d) * u.x * u.y;
}
const float2x2 M = float2x2(1.6, -1.2, 1.2, 1.6);
float fbm4(float2 p) { float v = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { v += a * vnoise(p); p = M * p; a *= 0.5; } return v; }
float fbm5(float2 p) { float v = 0.0, a = 0.5; for (int i = 0; i < 5; i++) { v += a * vnoise(p); p = M * p; a *= 0.5; } return v; }

half4 main(float2 fc) {
    float2 uv = fc / uRes;
    float asp = uRes.x / uRes.y;
    float2 p = float2((uv.x - 0.5) * asp, uv.y - 0.35) * 1.8;
    p *= 1.0 - 0.025 * uBeat;
    float t = uTime;
    float2 q = float2(fbm4(p + float2(t * 0.05, t * 0.07)),
                      fbm4(p + float2(5.2 - t * 0.06, 1.3 + t * 0.04)));
    float2 r = float2(fbm4(p + 2.5 * q + float2(1.7 + t * 0.11, 9.2)),
                      fbm4(p + 2.5 * q + float2(8.3, 2.8 - t * 0.09)));
    float f = fbm5(p + 2.0 * r);
    float smoke = clamp(0.35 + (f - 0.5) * 1.3, 0.0, 1.0);
    float rid = 1.0 - abs(2.0 * fbm5(p * 1.4 + 2.2 * r + float2(t * 0.03, 0.0)) - 1.0);
    float fil = pow(rid, 14.0 - 5.0 * uEnergy);
    float3 col = mix(uColA, uColB, smoke) + uColC * fil * (0.35 + 0.35 * uEnergy + 0.3 * uBeat);
    col *= 0.9 + 0.12 * uEnergy + 0.15 * uBeat;
    col = 1.0 - exp(-col * 1.6);
    float fade = pow(clamp(1.0 - (uv.y - uFadeStart) / uFadeLen, 0.0, 1.0), 1.6);
    return half4(half3(col * fade), 1.0);
}
"""

// endregion

/** Android < 13 и программный холст: несколько размытых пятен A/B/C, плывущих по кругу, затем то же затухание. */
private fun DrawScope.drawGradientNebula(a: Color, b: Color, c: Color, phase: Float, energy: Float, beat: Float) {
    val w = size.width
    val h = size.height
    val scale = 1f + 0.04f * beat + 0.05f * energy
    listOf(a, b, b, c).forEachIndexed { index, color ->
        val angle = phase * (0.12f + 0.05f * index) + index * 1.7f
        val cx = w * (0.5f + 0.34f * cos(angle))
        val cy = h * (0.24f + 0.12f * sin(angle * 1.3f + index))
        val r = maxOf(w, h) * (0.32f + 0.05f * index) * scale
        val alpha = if (color == c) 0.35f else 0.85f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.3f), Color.Transparent), Offset(cx, cy), r),
            radius = r,
            center = Offset(cx, cy),
        )
    }
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            FADE_START to Color.Transparent,
            FADE_START + FADE_LEN to Color.Black,
            1f to Color.Black,
        ),
    )
}

private const val PALETTE_MS = 800
private const val MAX_DT = 0.1f
private const val ECO_FRAME_S = 1f / 30f
private const val START_PHASE = 7f
private const val FADE_START = 0.42f
private const val FADE_LEN = 0.33f
