package ru.bodro.music.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.AccountRepository
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection

/** Доступ к синглтонам вне Hilt-инъекции (инструментальные тесты, отладка). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun accounts(): AccountRepository

    fun connection(): ConnectionManager

    fun database(): BodroDatabase

    fun settings(): SettingsRepository

    fun player(): PlayerConnection

    fun subsonic(): SubsonicClient
}
