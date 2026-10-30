plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

val supportedTvAbis = setOf("arm64-v8a", "armeabi-v7a")
val requestedTvAbi = providers.gradleProperty("tvAbi").orNull
val tvAbiFilters = requestedTvAbi?.let { abi ->
    require(abi in supportedTvAbis) {
        "Unsupported tvAbi '$abi'. Choose one of: ${supportedTvAbis.joinToString()}"
    }
    setOf(abi)
} ?: supportedTvAbis

// Nuvio Engine's release AAR is built with Kotlin 2.3, but this app must stay on
// Kotlin 2.0.21 for Room/KAPT and compileSdk 34 compatibility. Compile its pinned
// Kotlin wrapper sources with the app toolchain and extract only native libraries.
val nuvioEngineVersion = "0.1.6"
val nuvioEngineCommit = "167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3"
val nuvioEngineAarSha256 = "66b049a3625bfdc39c3b5d25a0af9bbc127c530512617e9f57cd988c019d33d6"
val nuvioEngineSources = listOf(
    "platform/android/engine/src/main/kotlin/com/nuvio/engine/NuvioEngine.kt" to "996ed238af9cfa1dd449adde14cfbc229e848899",
    "platform/android/engine/src/main/kotlin/com/nuvio/engine/NuvioEngineConfig.kt" to "27ad4c8f33ef4794417239aa265fc98242543a58",
    "platform/android/engine/src/main/kotlin/com/nuvio/engine/NuvioModels.kt" to "b7f1b18e9b8081e96f626b1ec3d6ec4712d91c10",
    "platform/android/engine/src/main/kotlin/com/nuvio/engine/internal/AndroidTrustStore.kt" to "d487c4397b6507befecced436bebbfc3140a90f0",
    "platform/android/engine/src/main/kotlin/com/nuvio/engine/internal/NativeBridge.kt" to "3ca61995ccc475e5937b6701d95b4f1b06bca959",
)
val nuvioEngineDocuments = listOf(
    "LICENSE" to "e72bfddabc15be5718a7cc061ac10e47741d8219",
    "THIRD_PARTY_NOTICES.md" to "379c4f1a8526a074be344fdffedd3caf1263a6c5",
)
val nuvioGeneratedSources = layout.buildDirectory.dir("generated/nuvio-engine/sources")
val nuvioGeneratedJniLibs = layout.buildDirectory.dir("generated/nuvio-engine/jniLibs")
val nuvioGeneratedAssets = layout.buildDirectory.dir("generated/nuvio-engine/assets")
val prepareNuvioEngine = tasks.register("prepareNuvioEngine") {
    inputs.property("nuvioEngineVersion", nuvioEngineVersion)
    inputs.property("nuvioEngineCommit", nuvioEngineCommit)
    inputs.property("nuvioEngineAarSha256", nuvioEngineAarSha256)
    inputs.property(
        "nuvioPinnedSourceBlobs",
        (nuvioEngineSources + nuvioEngineDocuments).joinToString("|"),
    )
    outputs.dir(nuvioGeneratedSources)
    outputs.dir(nuvioGeneratedJniLibs)
    outputs.dir(nuvioGeneratedAssets)

    doLast {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun download(url: String): ByteArray {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "StreamCloud-Gradle")
            }
            return connection.getInputStream().use { it.readBytes() }
        }

        val aarFile = layout.buildDirectory
            .file("downloads/nuvio-engine-android-$nuvioEngineVersion.aar")
            .get().asFile
        if (!aarFile.isFile || sha256(aarFile.readBytes()) != nuvioEngineAarSha256) {
            val bytes = download(
                "https://github.com/NuvioMedia/nuvio-engine/releases/download/" +
                    "v$nuvioEngineVersion/nuvio-engine-android-$nuvioEngineVersion.aar",
            )
            check(sha256(bytes) == nuvioEngineAarSha256) {
                "Nuvio Engine AAR checksum mismatch; refusing an unverified binary."
            }
            aarFile.parentFile.mkdirs()
            aarFile.writeBytes(bytes)
        }

        val sourceRoot = nuvioGeneratedSources.get().asFile
        val nativeRoot = nuvioGeneratedJniLibs.get().asFile
        val assetsRoot = nuvioGeneratedAssets.get().asFile
        sourceRoot.deleteRecursively()
        nativeRoot.deleteRecursively()
        assetsRoot.deleteRecursively()
        sourceRoot.mkdirs()
        nativeRoot.mkdirs()
        assetsRoot.mkdirs()

        fun fetchPinnedSource(upstreamPath: String, expectedBlob: String): ByteArray {
            val bytes = download(
                "https://raw.githubusercontent.com/NuvioMedia/nuvio-engine/" +
                    "$nuvioEngineCommit/$upstreamPath",
            )
            val blobPrefix = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
            val actualBlob = MessageDigest.getInstance("SHA-1")
                .digest(blobPrefix + bytes)
                .joinToString("") { "%02x".format(it) }
            check(actualBlob == expectedBlob) {
                "Nuvio Engine source checksum mismatch for $upstreamPath."
            }
            return bytes
        }

        nuvioEngineSources.forEach { (upstreamPath, expectedBlob) ->
            val bytes = fetchPinnedSource(upstreamPath, expectedBlob)
            val output = sourceRoot.resolve(upstreamPath.substringAfter("/kotlin/"))
            output.parentFile.mkdirs()
            output.writeBytes(bytes)
        }

        val licensesDirectory = assetsRoot.resolve("licenses")
        licensesDirectory.mkdirs()
        nuvioEngineDocuments.forEach { (upstreamPath, expectedBlob) ->
            val bytes = fetchPinnedSource(upstreamPath, expectedBlob)
            val outputName = when (upstreamPath) {
                "LICENSE" -> "GPL-3.0-or-later.txt"
                else -> "NuvioEngine-THIRD_PARTY_NOTICES.md"
            }
            licensesDirectory.resolve(outputName).writeBytes(bytes)
        }

        ZipFile(aarFile).use { archive ->
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
                val entry = archive.getEntry("jni/$abi/libnuvio_engine.so")
                    ?: error("Nuvio Engine AAR is missing the $abi native library.")
                val output = nativeRoot.resolve("$abi/libnuvio_engine.so")
                output.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
            }

            val classesJar = archive.getEntry("classes.jar")
                ?: error("Nuvio Engine AAR is missing its license-bearing classes.jar.")
            ZipInputStream(archive.getInputStream(classesJar)).use { nested ->
                while (true) {
                    val licenseEntry = nested.nextEntry ?: break
                    val entryName = licenseEntry.name
                    val isLicenseNotice = entryName.startsWith("META-INF/") &&
                        (entryName.contains("LICENSE", ignoreCase = true) ||
                            entryName.contains("NOTICE", ignoreCase = true))
                    if (isLicenseNotice && !licenseEntry.isDirectory) {
                        val output = licensesDirectory.resolve(entryName.substringAfterLast('/'))
                        output.outputStream().use { outputStream ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val count = nested.read(buffer)
                                if (count < 0) break
                                outputStream.write(buffer, 0, count)
                            }
                        }
                    }
                    nested.closeEntry()
                }
            }
        }
    }
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

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            buildConfigField("Boolean", "TV_BUILD", "false")
        }
        create("tv") {
            dimension = "device"
            buildConfigField("Boolean", "TV_BUILD", "true")
            // Keep both ARM ABIs in the universal TV APK for older Fire TV devices.
            // CI can pass -PtvAbi=arm64-v8a or -PtvAbi=armeabi-v7a to package one
            // architecture for the matching smaller update APK.
            ndk {
                abiFilters += tvAbiFilters
            }
        }
    }

    // Publish per-ABI APKs as well as a universal APK. The universal TV APK is
    // retained for existing installs/updaters; newer app versions select the
    // smaller APK matching the device's supported ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
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
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
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
            // jackson-core and PipePipe's fastdoubleparser both package this text resource.
            excludes += "META-INF/thirdparty-LICENSE"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets.getByName("main").java.srcDir(nuvioGeneratedSources)
    sourceSets.getByName("main").jniLibs.srcDir(nuvioGeneratedJniLibs)
    sourceSets.getByName("main").assets.srcDir(nuvioGeneratedAssets)
}

tasks.configureEach {
    if (
        (name.startsWith("compile") && name.endsWith("Kotlin")) ||
        name.startsWith("kaptGenerateStubs") ||
        name.endsWith("JniLibFolders") ||
        name.endsWith("Assets")
    ) {
        dependsOn(prepareNuvioEngine)
    }
}

// Pin transitive dependency versions that haze would otherwise upgrade beyond
// what this project's compileSdk / toolchain supports.
//
// haze 1.5.4 (via org.jetbrains.compose) pulls in:
//   - androidx.activity 1.10.x  → requires compileSdk 35 (we use 34)
//   - kotlin-stdlib    2.1.0    → compiler emits metadata 2.1.0; Room kapt
//                                  (kotlinx-metadata-jvm bundled in Room) caps
//                                  at 2.0.0 and crashes. Kotlin compiler plugin
//                                  is 2.0.21 so stdlib must match.
configurations.all {
    resolutionStrategy {
        force("androidx.activity:activity:1.9.2")
        force("androidx.activity:activity-ktx:1.9.2")
        force("androidx.activity:activity-compose:1.9.2")
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
        // PipePipe and BravePipe ship different nanojson revisions. Use the newer common
        // revision so both extractors can run in the same APK.
        force("com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166")
    }
    // PipePipe brings protobuf-java while BravePipe uses protobuf-javalite. Keeping both produces
    // duplicate DEX classes; the lightweight Android variant is sufficient for both extractors.
    exclude(group = "com.google.protobuf", module = "protobuf-java")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Required for plugins that use View-based Material components such as
    // BottomSheetDialogFragment, TextInputLayout, MaterialAlertDialogBuilder, etc.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Compose BOM — 2024.09.03 ships Runtime 1.7.x which is required by the
    // Kotlin 2.0 Compose Compiler Plugin (kotlin.plugin.compose). Runtime 1.6.x
    // (shipped by 2024.06.00) is missing Composer APIs the new compiler generates
    // calls to, causing AbstractMethodError / NoSuchMethodError on first composable.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Kotlin Coroutines + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Networking (Retrofit + OkHttp + official Kotlinx serialization converter)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    // OkHttp 4.12 is compatible with the app's Kotlin 2.0 compiler and compileSdk 34.
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

    // Nuvio Engine's pinned wrapper sources and verified ABI libraries are
    // prepared by prepareNuvioEngine; the upstream Kotlin 2.3 AAR classes are not linked.

    // Maintained YouTube extractors. PipePipe is the preferred music resolver; BravePipe keeps an
    // independent parser/decipher fallback when a PipePipe stream URL is rejected by the CDN.
    // Both share the NewPipe package API, so BravePipe replaces the older upstream dependency.
    implementation("com.github.maxrave-dev:PipePipeExtractor:208e43b184") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "org.mozilla", module = "rhino-engine")
        exclude(group = "org.mozilla", module = "rhino-runtime")
        // PipePipe uses StreamCloud's Downloader implementation, not OkHttp directly. Its
        // packaged OkHttp 5.4 runtime requires Android SDK 36 and Kotlin 2.2 metadata, neither
        // of which is compatible with StreamCloud's existing Android/Kotlin toolchain.
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okhttp3", module = "okhttp-android")
    }
    implementation("com.github.maxrave-dev:BravePipeExtractor:fa5d4a8b4c") {
        // BravePipe uses Rhino to decipher current YouTube player JavaScript. Do not replace this
        // with the app's unrelated QuickJS integration: their Java APIs are not interchangeable.
    }
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
    implementation("org.jsoup:jsoup:1.17.2")

    // WorkManager (download queue)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Room for Library (liked + recently played).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

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
    // We pin to alpha09 because it's the last release built with Kotlin 1.9.22 —
    // newer versions require Kotlin 2.0+ which would force-upgrade our entire
    // toolchain (Compose compiler, kapt, ksp, etc.).
    implementation("io.github.dokar3:quickjs-kt-android:1.0.0-alpha09")

    // Jackson — required by CloudStream `.cs3` plugins that call
    // `MainActivityKt.mapper.readValue(...)` or `parsedSafe<...>()`. Without it,
    // every plugin dies with NoClassDefFoundError on first JSON parse.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Google Cast (Chromecast). Adds the `androidx.mediarouter` chooser dialog
    // and the cast framework that powers MediaRouteButton + RemoteMediaClient.
    // Uses the Default Media Receiver app id — no developer console needed.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // Haze — real background-blur / frosted-glass effect for the floating nav pill.
    // Same library and version used by Nuvio.
    implementation("dev.chrisbanes.haze:haze:1.5.4")

}
