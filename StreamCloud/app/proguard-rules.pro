# --- Keep Kotlinx Serialization metadata ---
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep class kotlinx.serialization.** { *; }

# --- Retrofit / OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# --- Coil ---
-keep class coil.** { *; }

# --- Jsoup ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- NewPipe Extractor ---
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# --- Media3 / ExoPlayer ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Compose ---
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- App models (kotlinx serialization) ---
-keep class com.aioweb.app.data.api.** { *; }

# --- CloudStream plugin API stubs (called reflectively by .cs3 plugins via DexClassLoader) ---
-keep class com.lagradost.** { *; }
-keepclassmembers class com.lagradost.** { *; }
-dontwarn com.lagradost.**

# --- Plugin loading infrastructure (PluginRuntime uses DexClassLoader + reflection) ---
-keep class com.streamcloud.app.data.plugins.** { *; }
-keepclassmembers class com.streamcloud.app.data.plugins.** { *; }

# --- Jackson (used by MainAPI / cloudstream3 stubs) ---
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
