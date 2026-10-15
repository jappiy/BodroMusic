package ru.bodro.music.core.subsonic

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import ru.bodro.music.core.model.ServerKind

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "fixture $name not found" }.readText()

internal fun json(name: String, code: Int = 200): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .body(fixture(name))
    .build()

internal fun testHttp(timeoutMs: Long = 2_000): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
    .build()

internal fun MockWebServer.config(password: String = "sesame") =
    ConnectionConfig(url("/"), ServerKind.Local, "vasya", password)

internal fun MockWebServer.client(http: OkHttpClient = testHttp(), salt: String = "c19b2d") =
    SubsonicClient(http, { config() }, saltGenerator = { salt })
