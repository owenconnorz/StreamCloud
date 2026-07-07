# Plugin Development Guide for StreamCloud

StreamCloud supports CloudStream 3 plugins (.cs3 files) with enhanced ExtractorAPI support.

## Table of Contents

1. [Plugin Basics](#plugin-basics)
2. [Building a Plugin](#building-a-plugin)
3. [Registering MainAPI Providers](#registering-mainapi-providers)
4. [Registering Custom Extractors](#registering-custom-extractors)
5. [Testing Your Plugin](#testing-your-plugin)
6. [Troubleshooting](#troubleshooting)
7. [Compatibility Notes](#compatibility-notes)

## Plugin Basics

A StreamCloud plugin is a compiled Kotlin/Java library packaged as a `.cs3` file (which is a standard JAR/ZIP archive containing a `classes.dex` file).

Plugins must:
- Implement a class extending `com.lagradost.cloudstream3.plugins.Plugin`
- Override the `load(Context)` method to register APIs
- Call `registerMainAPI()` and/or `registerExtractorAPI()` during initialization
- Be compiled against the CloudStream 3 plugin SDK

### Plugin Class Structure

```kotlin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

class MyPlugin : Plugin() {
    override fun load(context: Context) {
        // Register content providers
        registerMainAPI(MyContentProvider())
        
        // Register custom extractors
        registerExtractorAPI(MyCustomExtractor())
    }
}
```

## Building a Plugin

### Prerequisites
- Android SDK 26+
- Kotlin 1.8+
- CloudStream 3 plugin SDK dependency

### Gradle Setup

```gradle
dependencies {
    // CloudStream 3 plugin SDK
    compileOnly("com.lagradost:cloudstream3:21.14.13")
}

android {
    namespace = "com.example.myplugin"
    compileSdk = 35
    
    defaultConfig {
        minSdk = 26
        targetSdk = 35
    }
}
```

### Build Command

```bash
./gradlew build
```

This produces a `.aar` file that must be converted to `.dex` and packaged as `.cs3`:

```bash
# Convert AAR to DEX
d8 --output=dex.jar build/outputs/aar/mylib-release.aar

# Package as .cs3
zip myplugin.cs3 classes.dex manifest.json
```

## Registering MainAPI Providers

MainAPI providers are content source plugins that provide search, details, and link extraction.

### Basic MainAPI Implementation

```kotlin
import com.lagradost.cloudstream3.*

class MyProvider : MainAPI() {
    override val name = "My Provider"
    override val mainUrl = "https://example.com"
    override val hasMainPage = true
    
    override val mainPage = listOf(
        MainPageRequest("Home", "", horizontalImages = false),
        MainPageRequest("Popular", "popular", horizontalImages = false),
    )
    
    override suspend fun search(query: String): List<SearchResponse>? {
        // Implement search logic
        return emptyList()
    }
    
    override suspend fun load(url: String): LoadResponse? {
        // Implement detail page loading
        return null
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Implement link extraction
        return false
    }
}
```

### In Your Plugin

```kotlin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
    }
}
```

## Registering Custom Extractors

ExtractorAPI providers are specialized plugins that extract video streams from specific hosting services.

### Basic ExtractorAPI Implementation

```kotlin
import com.lagradost.cloudstream3.utils.*

class MyExtractor : ExtractorApi() {
    override val name = "MyHosting"
    override val mainUrl = "https://myhosting.com"
    override val requiresReferer = true
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Extract and provide links
        callback(
            ExtractorLink(
                name = name,
                source = name,
                url = "https://...",
                referer = referer,
                quality = Qualities.HD.value,
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}
```

### In Your Plugin

```kotlin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
        registerExtractorAPI(MyExtractor())  // NEW: Custom extractor
    }
}
```

## Testing Your Plugin

### Local Testing

1. Build your plugin: `./gradlew build`
2. Convert to .cs3 format (see Building section)
3. Copy to StreamCloud's plugin directory
4. Restart the app and load the plugin

### Error Checking

Monitor logcat for errors:

```bash
adb logcat | grep "PluginInstance\|PluginRuntime"
```

Common error messages:
- `NoClassDefFoundError` — Missing dependency
- `ClassCastException` — Classloader mismatch
- `NoSuchMethodError` — API incompatibility

## Troubleshooting

### Plugin Won't Load

**Error: "Could not find plugin class"**
- Ensure your plugin class extends `Plugin` or `MainAPI`
- Verify the class is in `manifest.json` as `pluginClassName`
- Check that the .cs3 file contains a valid `classes.dex`

**Error: "NoClassDefFoundError"**
- Plugin references a missing dependency
- Add missing dependencies to your gradle build
- Ensure all transitive dependencies are included in the final .cs3

**Error: "ClassCastException"**
- Classloader mismatch — StreamCloud will attempt to recover with fallback loaders
- This usually resolves automatically; if not, rebuild your plugin against the exact CloudStream version

### Plugin Loads But Has No Content

- Check the plugin's `load()` method is being called
- Verify `registerMainAPI()` is called with a valid provider
- Add logging to your plugin to debug

```kotlin
override fun load(context: Context) {
    Log.d("MyPlugin", "Loading plugin...")
    registerMainAPI(MyProvider())
    Log.d("MyPlugin", "Provider registered")
}
```

## Compatibility Notes

### Supported CloudStream Versions

StreamCloud is compatible with CloudStream 3 plugins built for SDK versions:
- 21.11.0 and later

### API Stability

The following core APIs are stable:
- `MainAPI` — Base class for content providers
- `SearchResponse` — Search result objects
- `LoadResponse` — Content details
- `ExtractorLink` — Video stream links
- `ExtractorApi` — Custom extractor base class (NEW)

### Breaking Changes

If you build against a newer CloudStream SDK and it doesn't work:

1. Check the CloudStream changelog for API changes
2. Update your plugin to match the new API
3. Rebuild and test

StreamCloud's reflection bridge may help with minor API incompatibilities, but significant SDK changes will require plugin updates.

### Plugin Settings

Plugins can provide settings UI via the `openSettings` callback:

```kotlin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
        
        setOpenSettings {
            // Show settings activity
            val intent = Intent(it, MySettingsActivity::class.java)
            it.startActivity(intent)
        }
    }
}
```

## Resources

- [CloudStream 3 Repository](https://github.com/LagradOst/CloudStream-3)
- [StreamCloud Repository](https://github.com/owenconnorz/StreamCloud)
- [CloudStream Plugin Examples](https://github.com/search?q=cloudstream+plugin)

---

For support, join the StreamCloud Discord community: https://discord.gg/z62jDev7t3
