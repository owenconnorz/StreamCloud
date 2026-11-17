import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}
val keyStorePath = System.getenv("KEYSTORE_PATH") ?: keystoreProps["storeFile"]?.toString()
val releaseKeyStore = keyStorePath
    ?.let { file(it) }
    ?.takeIf { it.exists() }
    ?: rootProject.file("streamcloud-debug.jks")
val usesConfiguredKey = keyStorePath != null && releaseKeyStore.absolutePath == keyStorePath

android {
    namespace = "com.streamcloud.app.wear"
    compileSdk = 34

    defaultConfig {
        // Wear Data Layer messages are package-scoped: the phone and watch apps
        // must share an application ID and signing key, while installing on
        // separate devices.
        applicationId = "com.streamcloud.app"
        minSdk = 30
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeyStore
            storePassword = if (usesConfiguredKey) {
                System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps["storePassword"]?.toString()
            } else {
                "streamcloud"
            }
            keyAlias = if (usesConfiguredKey) {
                System.getenv("KEY_ALIAS") ?: keystoreProps["keyAlias"]?.toString()
            } else {
                "streamcloud-debug"
            }
            keyPassword = if (usesConfiguredKey) {
                System.getenv("KEY_PASSWORD") ?: keystoreProps["keyPassword"]?.toString()
            } else {
                "streamcloud"
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
