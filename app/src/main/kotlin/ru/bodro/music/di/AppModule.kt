package ru.bodro.music.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ru.bodro.music.BuildConfig
import ru.bodro.music.core.subsonic.NavidromeLogInterceptor
import ru.bodro.music.core.subsonic.PingServerProbe
import ru.bodro.music.core.subsonic.ServerSelector
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.CredentialStore
import ru.bodro.music.data.account.TinkCredentialStore
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.network.CleartextPolicyInterceptor
import ru.bodro.music.data.settings.SettingsRepository

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BodroDatabase =
        Room.databaseBuilder(context, BodroDatabase::class.java, BodroDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun credentialStore(impl: TinkCredentialStore): CredentialStore
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun logInterceptor(
        log: Lazy<LogRepository>,
        settings: Lazy<SettingsRepository>,
        connection: Lazy<ConnectionManager>,
    ): NavidromeLogInterceptor = NavidromeLogInterceptor(
        sink = { log.get().log(it) },
        level = { settings.get().current.logLevel },
        serverKindOf = { connection.get().serverKindOf(it) },
    )

    /** Один клиент на всё: API, стрим (ExoPlayer), обложки (Coil) — одинаковые логи и правила cleartext. */
    @Provides
    @Singleton
    fun okHttp(logInterceptor: NavidromeLogInterceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(CleartextPolicyInterceptor())
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "BodroMusic/${BuildConfig.VERSION_NAME} (Android)")
                    .build(),
            )
        }
        .addInterceptor(logInterceptor)
        .build()

    @Provides
    @Singleton
    fun serverSelector(http: OkHttpClient): ServerSelector = ServerSelector(PingServerProbe(http))

    @Provides
    @Singleton
    fun subsonicClient(http: OkHttpClient, connection: ConnectionManager): SubsonicClient = SubsonicClient(http, connection)
}
