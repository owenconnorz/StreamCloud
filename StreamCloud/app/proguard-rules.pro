# ═══════════════════════════════════════════════════════════════════════════
# StreamCloud proguard rules
#
# isMinifyEnabled=true is required to use R8 for DEX merging, which silently
# deduplicates kotlin.coroutines.jvm.internal.SpillingKt that is embedded in
# an external library's JAR compiled with an older Kotlin.  D8 (minify=false)
# would error on the duplicate; R8 picks the first occurrence (our Kotlin
# 2.1.20 stdlib version with nullOutSpilledVariable) and discards the rest.
#
# All app code is fully kept — this is NOT a shrinking/obfuscation build.
# ═══════════════════════════════════════════════════════════════════════════

# ── Disable shrinking / obfuscation — keep everything the app needs ───────
-dontobfuscate
-dontoptimize
-dontshrink

# ── Suppress benign warnings from reflection-heavy libraries ──────────────
-dontwarn **

# ── StreamCloud app ───────────────────────────────────────────────────────
-keep class com.streamcloud.app.** { *; }

# ── CloudStream plugin API (lagradost) ────────────────────────────────────
-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }

# ── Kotlin runtime ────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# ── Kotlinx Serialization ─────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep class kotlinx.serialization.** { *; }

# ── Jackson (used by CloudStream plugins for JSON parsing) ────────────────
-keep class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.JsonProperty class * { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonCreator *;
}

# ── Retrofit / OkHttp ────────────────────────────────────────────────────
-keep class retrofit2.** { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── Room (KSP-generated DAOs and entities) ────────────────────────────────
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────
-keep class androidx.media3.** { *; }

# ── Compose ──────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }

# ── Coil image loading ────────────────────────────────────────────────────
-keep class coil.** { *; }

# ── Jsoup ────────────────────────────────────────────────────────────────
-keep class org.jsoup.** { *; }

# ── NewPipe Extractor ────────────────────────────────────────────────────
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }

# ── QuickJS ───────────────────────────────────────────────────────────────
-keep class io.github.dokar3.quickjs.** { *; }

# ── Google Cast ───────────────────────────────────────────────────────────
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class androidx.mediarouter.** { *; }

# ── WorkManager ───────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }

# ── DataStore / Preferences ───────────────────────────────────────────────
-keep class androidx.datastore.** { *; }

# ── Reflection helpers (DexClassLoader for CloudStream plugins) ───────────
-keepclassmembers class * {
    public <init>(...);
}

# ── Keep all annotation types ─────────────────────────────────────────────
-keepattributes *Annotation*
