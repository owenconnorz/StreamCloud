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
