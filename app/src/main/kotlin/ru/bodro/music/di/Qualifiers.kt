package ru.bodro.music.di

import javax.inject.Qualifier

/** Корутин-скоуп уровня приложения (живёт, пока жив процесс). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/** Часы (для тестов). */
fun interface Clock {
    fun now(): Long
}
