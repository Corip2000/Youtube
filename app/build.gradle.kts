// Импорт обязателен: в Gradle Kotlin DSL `java` — это свойство проекта
// (расширение Java-плагина), оно перекрывает пакет java.*, и записать
// java.util.Properties напрямую нельзя.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Необязательная подпись своим ключом. Нужна только если хочешь вход в Google:
// SHA-1 в Google Cloud должен совпадать с ключом, которым подписан APK.
// Положи keystore.jks в папку app/ и создай app/keystore.properties.
val keystoreFile = file("keystore.jks")
val keystoreProps: Properties? = file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { propsFile ->
        Properties().apply { propsFile.inputStream().use { stream -> load(stream) } }
    }

android {
    namespace = "ru.corip.shortsoffline"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.corip.shortsoffline"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["appAuthRedirectScheme"] = "ru.corip.shortsoffline"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("app") {
            if (keystoreFile.exists() && keystoreProps != null) {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists() && keystoreProps != null) {
                signingConfig = signingConfigs.getByName("app")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("net.openid:appauth:0.11.1")

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
}
