import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Версия приложения. Каждое изменение функциональности — запись в CHANGELOG.md и повышение версии.
val appVersionCode = 30
val appVersionName = "0.29.0"

val gitHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short=10", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

val buildTime: String = Instant.now().toString()

/** CHANGELOG.md из корня репозитория встраивается в assets (экран «История версий»). */
val changelogAssetsDir = layout.buildDirectory.dir("generated/changelogAssets")
val copyChangelog by tasks.registering(Copy::class) {
    from(rootProject.file("CHANGELOG.md"))
    into(changelogAssetsDir)
}

android {
    namespace = "ru.bodro.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.bodro.music"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        // Данные тестовой учётки Navidrome — только из local.properties / окружения, не из git.
        val localProps = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        fun testProp(key: String, env: String) = localProps.getProperty(key) ?: System.getenv(env) ?: ""
        testInstrumentationRunnerArguments["navidromeUrl"] = testProp("navidrome.test.url", "NAVIDROME_TEST_URL")
        testInstrumentationRunnerArguments["navidromeUser"] = testProp("navidrome.test.user", "NAVIDROME_TEST_USER")
        testInstrumentationRunnerArguments["navidromePassword"] = testProp("navidrome.test.password", "NAVIDROME_TEST_PASSWORD")
    }

    // Постоянный ключ BodroMusic: в CI релиза — файл из секрета SIGNING_KEYSTORE_B64 (переменная BODRO_KEYSTORE),
    // на компьютере владельца — его ~/.android/debug.keystore (стандартная debug-подпись). Это один и тот же ключ
    // (SHA-256 сертификата c5146426…94de62). Не менять: с другой подписью Android не ставит обновление поверх.
    val bodroKeystore = System.getenv("BODRO_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (bodroKeystore != null) {
            create("bodro") {
                storeFile = file(bodroKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    val appSigning = signingConfigs.findByName("bodro") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = appSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = appSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(changelogAssetsDir)

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

room {
    // Схемы БД для миграций; плагин Room корректно разводит варианты debug/release.
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

tasks.named("preBuild") { dependsOn(copyChangelog) }

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:subsonic"))
    implementation(project(":core:domain"))
    implementation(project(":core:downloads"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.database)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.core)
}
