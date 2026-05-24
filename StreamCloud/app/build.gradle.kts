plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.streamcloud.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamcloud.app"
        minSdk = 24
        targetSdk = 34
        // Use CI run number as versionCode so each successive build is treated as an update.
        // Falls back to 1 for local builds.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // Backend base URL (override in Settings screen at runtime via DataStore)
        buildConfigField(
            "String", "DEFAULT_BACKEND_URL",
            "\"https://aio-android-port.preview.emergentagent.com\""
        )
        // TMDB v3 API key (free public dev key – users can override in Settings)
        buildConfigField("String", "TMDB_API_KEY", "\"8265bd1679663a7ea12ac168da84d2e8\"")

        // GitHub repo for the in-app updater. CI auto-injects from `GITHUB_REPOSITORY`
        // (`owner/name`); local builds default to the original AioWeb repo.
        val ghRepository = System.getenv("GITHUB_REPOSITORY") ?: "owenconnorz/AioWeb"
        val ghOwner = ghRepository.substringBefore('/')
        val ghName  = ghRepository.substringAfter('/')
        buildConfigField("String", "GITHUB_OWNER", "\"$ghOwner\"")
        buildConfigField("String", "GITHUB_REPO",  "\"$ghName\"")
    }

    signingConfigs {
        // Fallback debug-style keystore committed at StreamCloud/streamcloud-debug.jks.
        // Successive CI builds use the same key, so the in-app updater can install updates
        // over previous releases. Anyone can replace it by setting KEYSTORE_PATH (or
        // KEYSTORE_BASE64 in CI) to a real release keystore.
        val fallbackKs = rootProject.file("streamcloud-debug.jks")

        create("release") {
            val ksPathEnv = System.getenv("KEYSTORE_PATH") ?: keystoreProps["storeFile"]?.toString()
            val ksPath = ksPathEnv?.let { file(it) }?.takeIf { it.exists() } ?: fallbackKs
            val realKey = ksPathEnv != null && ksPath.absolutePath == ksPathEnv

            storeFile = ksPath
            storePassword = if (realKey) System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps["storePassword"]?.toString() else "streamcloud"
            keyAlias = if (realKey) System.getenv("KEY_ALIAS") ?: keystoreProps["keyAlias"]?.toString() else "streamcloud-debug"
            keyPassword = if (realKey) System.getenv("KEY_PASSWORD") ?: keystoreProps["keyPassword"]?.toString() else "streamcloud"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Always sign release — fallback keystore guarantees this works even with no secrets.
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Reuse the same keystore so each successive CI debug build can update the previous.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn", "-Xallow-kotlin-package")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
        jniLibs {
            useLegacyPackaging = true
            // Prevent AGP's llvm-strip from re-stripping the already-stripped
            // TorrServer binary — double-stripping corrupts .gnu.version_r
            // string table, turning "LIBC" → "version"/"oid" which causes
            // the Android linker to fail with exit=127.
            keepDebugSymbols += "**/libtorrserver.so"
        }
    }
}

// Force all transitive dependencies to use the same kotlin-stdlib as our Kotlin compiler.
// Without this, libraries compiled with older Kotlin (e.g. quickjs-kt alpha09 / Kotlin 1.9)
// can pull in an older stdlib whose SpillingKt lacks nullOutSpilledVariable — a method
// that Kotlin 2.0+ generates calls to in every suspend function with spilled variables.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" &&
                requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.1.20")
            because("Keep Kotlin stdlib in sync with Kotlin compiler 2.1.20")
        }
    }
}

// ── Workaround: Kotlin 2.1.x K2 compiler also emits SpillingKt.class ─────
// The class is defined in kotlin-stdlib-2.1.x.jar AND generated by the K2
// compiler into build/tmp/kotlin-classes/<variant>/.  D8/R8 reject the
// duplicate with "Type is defined multiple times".  Removing the redundant
// compiler-generated copy (not the stdlib one) is the surgical fix.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach {
        doLast {
            val kotlinClassesDir = project.layout.buildDirectory
                .dir("tmp/kotlin-classes").get().asFile
            fileTree(kotlinClassesDir) {
                include("**/kotlin/coroutines/jvm/internal/SpillingKt.class")
            }.forEach { f ->
                f.delete()
                logger.lifecycle("Deleted compiler-generated SpillingKt duplicate: ${f.absolutePath}")
            }
        }
    }


dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Kotlin Coroutines + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Networking (Retrofit + OkHttp + official Kotlinx serialization converter)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    // GIF / WEBP animation decoders — used by the Reddit feed cards (Adult tab)
    // for animated NSFW posts. Without this, AsyncImage renders only the first
    // frame.
    implementation("io.coil-kt:coil-gif:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Media3 ExoPlayer (audio + video)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // OkHttp-backed data source for Media3 — replaces DefaultHttpDataSource in the
    // download pipeline so downloads get HTTP/2, connection pooling, and socket-level
    // buffer tuning instead of bare HttpURLConnection.
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // Torrent / P2P streaming via TorrServer (Go binary in jniLibs/).
    // TorrServer exposes a local HTTP server with Range support that ExoPlayer
    // can stream from directly — no Java-level piece-gating needed.
    // (libtorrent4j + nanohttpd replaced; see com.streamcloud.app.torrent.*)

    // NewPipe Extractor (YouTube music/videos without API keys)
    // We exclude its transitive Rhino artifacts; we already pull mainline
    // `org.mozilla:rhino` ourselves below for Nuvio JS providers, and
    // duplicated Rhino jars trigger D8's "Duplicate class" failure.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "org.mozilla", module = "rhino-engine")
        exclude(group = "org.mozilla", module = "rhino-runtime")
    }
    implementation("org.jsoup:jsoup:1.17.2")

    // WorkManager (download queue)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Room for Library (liked + recently played).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Androidx Palette — extracts dominant color from album art for Metrolist-style
    // gradient backgrounds in the Now Playing sheet.
    implementation("androidx.palette:palette-ktx:1.0.0")

    // QuickJS via JNI — used to run Nuvio's local-scraper providers (the .js files
    // from yoruix/nuvio-providers, D3adlyRocket/All-in-One-Nuvio, phisher98/...).
    //
    // We previously tried Mozilla Rhino (1.7.x and 1.8.x) but Rhino can't parse
    // ES2017+ syntax — every modern Nuvio provider uses `async`/`await` and
    // object rest destructuring, both of which Rhino chokes on. QuickJS supports
    // full ES2020+ natively (it's the same engine used by Bun, edge runtimes, etc.).
    //
    // alpha13 is built with Kotlin 2.0 and is compatible with our Kotlin 2.1.20 toolchain.
    // alpha09 (Kotlin 1.9.22) caused a NoSuchMethodError on SpillingKt.nullOutSpilledVariable
    // because the older stdlib it bundled doesn't have the method that Kotlin 2.0 generates calls to.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.0-alpha13")

    // Gson — required by CloudStream plugins (e.g. ShowBox) that call Gson APIs
    // directly. Plugins load via DexClassLoader with the app as parent, so Gson
    // must be present in the app's own classpath.
    implementation("com.google.gson:gson:2.10.1")

    // Jackson — required by CloudStream `.cs3` plugins that call
    // `MainActivityKt.mapper.readValue(...)` or `parsedSafe<...>()`. Without it,
    // every plugin dies with NoClassDefFoundError on first JSON parse.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Google Cast (Chromecast). Adds the `androidx.mediarouter` chooser dialog
    // and the cast framework that powers MediaRouteButton + RemoteMediaClient.
    // Uses the Default Media Receiver app id — no developer console needed.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
}
