# Add project specific ProGuard rules here.

# ── Moshi ──────────────────────────────────────────────────────────────────────
# Keep Moshi-generated JsonAdapter classes
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Keep @JsonClass-annotated classes and their generated adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# ── Gson ───────────────────────────────────────────────────────────────────────
# Keep TypeToken generic signatures (used in AddonConfigServer/RepositoryConfigServer)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────────────────
# Keep generic signatures for Retrofit service methods
-keepattributes Signature
# Keep Retrofit service interfaces (must preserve generic return types)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# NOTE: allowobfuscation here is fine for Retrofit, but superseded by the
# broader kotlin.** keep rule below for DexClassLoader extension compatibility.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all project API interfaces
-keep class com.nuvio.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Conscrypt (installed as a JSSE security Provider at runtime) ────────────────
# PluginRuntimeHooks does Security.insertProviderAt(Conscrypt.newProvider(), ...).
# The provider + its algorithm impls are resolved reflectively by JSSE — keep them.
-keep class org.conscrypt.** { *; }
-keepclassmembers class org.conscrypt.** { *; }

# ── ZXing (QR generation for addon management) ─────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── Data classes (DTOs / persisted models) ──────────────────────────────────────
# Keep all DTO/model classes (de)serialized from backend JSON or persisted to
# DataStore via Gson/Moshi/kotlinx-serialization. Keeping fields (not just the
# class) is required so reflective (de)serialization can read/write them.
-keep class com.nuvio.tv.data.remote.dto.** { *; }
-keep class com.nuvio.tv.domain.model.** { *; }
# Gson is used directly to persist these to DataStore / drive the local server,
# and many carry no annotations — keep their fields by name.
-keep class com.nuvio.tv.data.local.** { *; }
-keep class com.nuvio.tv.core.reco.** { *; }
-keep class com.nuvio.tv.core.streams.** { *; }
-keep class com.nuvio.tv.core.sync.** { *; }
-keep class com.nuvio.tv.core.network.** { *; }
# Keep all enums' name()/valueOf() — enums are serialized by name in JSON/DataStore.
-keepclassmembers enum com.nuvio.tv.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin Metadata for reflection
-keepattributes RuntimeVisibleAnnotations

# ── NanoHTTPD (used by local server) ───────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
# Keep server classes and their inner data classes (serialized with Gson)
-keep class com.nuvio.tv.core.server.** { *; }

# ── Torrent streaming (TorrServer) ─────────────────────────────────────────────
-keep class com.nuvio.tv.core.torrent.** { *; }

#── QuickJS ────────────────────────────────────────────────────────────────────
# Keep quickjs-kt library classes for proper type conversion
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
# Keep PluginRuntime and related classes for JS bindings
-keep class com.nuvio.tv.core.plugin.** { *; }
-keepclassmembers class com.nuvio.tv.core.plugin.** { *; }

# ── ExoPlayer / Media3 ────────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }

# ── Supabase / Ktor / Kotlinx Serialization ───────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.nuvio.tv.data.remote.supabase.** { *; }

# Official kotlinx.serialization R8/ProGuard rules.
# Keep the runtime serialization library + generated $$serializer classes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }
# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the generated serializer classes themselves and their members.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$$serializer {
    *;
}
# Belt-and-suspenders: keep any explicitly named serializer member.
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep ALL app classes that are kotlinx-@Serializable (fields + serializers).
# @Serializable models live across many packages (core.network, core.reco,
# core.streams, core.sync, data.local, data.remote.supabase, ui.screens.account).
-keep @kotlinx.serialization.Serializable class com.nuvio.tv.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.nuvio.tv.** {
    *** Companion;
    <fields>;
}

# ── External extension compatibility stubs (loaded via DexClassLoader) ────────
-keep class com.lagradost.cloudstream3.** { *; }
-keepclassmembers class com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-keepclassmembers class com.lagradost.nicehttp.** { *; }
-keep class com.lagradost.api.** { *; }
-keepclassmembers class com.lagradost.api.** { *; }

# ── @Keep ────────────────────────────────────────────────────────────────────
# Honor androidx.annotation.Keep anywhere it is applied (e.g. CollectionsDataStore).
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
# Hilt ships its own consumer rules, but keep generated components defensively.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules** { *; }
-dontwarn dagger.hilt.**

# ── Coil ──────────────────────────────────────────────────────────────────────
-dontwarn coil3.**
-dontwarn coil.**

# ── ZXing / NanoHTTPD already kept above ──────────────────────────────────────

# ── General ────────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MPV (native JNI callbacks)
# Native code reflects into multiple classes/methods under is.xyz.mpv,
# so keep the whole package to avoid JNI lookup crashes after R8.
-keep class is.xyz.mpv.** { *; }

# ── Missing class stubs (referenced by cloudstream3 / jsoup / newpipe) ────────
-dontwarn org.mozilla.javascript.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn okhttp3.internal.sse.**
-dontwarn org.jsoup.helper.Re2jRegex

# ── DexClassLoader runtime deps (CloudStream extensions) ─────────────────────
# Extensions are DEX files loaded at runtime via DexClassLoader. They resolve
# dependencies by fully-qualified name from the host classloader. R8 must not
# rename or remove any class that extensions may reference.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient