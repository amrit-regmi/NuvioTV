plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.io.File
import java.util.Properties

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun resolveLocalProperty(local: Properties, key: String, fallback: String = ""): String {
    return local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun cmakePath(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    val resolved = if (file.isAbsolute) file else rootProject.file(path)
    return resolved.absolutePath.replace("\\", "/")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK")
)
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")
val sponsorNames = resolveProperty(devProperties, localProperties, "SPONSOR_NAMES", "ragmehos.")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun truthy(value: String?): Boolean {
    return value.equals("true", ignoreCase = true) ||
        value.equals("1", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)
}

val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val useLocalFfmpegDecoder = truthy(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: env("USE_LOCAL_FFMPEG_DECODER")
        ?: localProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
// Release signing material comes ONLY from env vars or local.properties.
// There are intentionally NO baked fallback values: a missing key fails the
// signed release build (see the fail-fast check below) instead of silently
// signing with a hardcoded password.
val releaseStoreFilePath = env("NUVIO_RELEASE_STORE_FILE")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_FILE")
val releaseKeyAliasValue = env("NUVIO_RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_ALIAS")
val releaseKeyPasswordValue = env("NUVIO_RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_PASSWORD")
val releaseStorePasswordValue = env("NUVIO_RELEASE_STORE_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD")

val missingReleaseSigningKeys = buildList {
    if (releaseKeyAliasValue.isNullOrBlank()) add("NUVIO_RELEASE_KEY_ALIAS")
    if (releaseKeyPasswordValue.isNullOrBlank()) add("NUVIO_RELEASE_KEY_PASSWORD")
    if (releaseStorePasswordValue.isNullOrBlank()) add("NUVIO_RELEASE_STORE_PASSWORD")
}
// Fail fast when a signed release/bundle is requested without complete signing
// material. CI_USE_DEBUG_SIGNING=true (beta/dry-run builds) legitimately skips
// the release keystore, so it is exempt.
if (missingReleaseSigningKeys.isNotEmpty() && !useDebugReleaseSigning) {
    val requestsSignedRelease = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("Release", ignoreCase = true) ||
            taskName.contains("bundle", ignoreCase = true)
    }
    if (requestsSignedRelease) {
        throw GradleException(
            "Missing release signing configuration: ${missingReleaseSigningKeys.joinToString()}. " +
                "Provide these via environment variables or local.properties. " +
                "Hardcoded fallbacks were removed and will not be restored."
        )
    }
}

android {
    namespace = "com.nuvio.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nuvio.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1042
        versionName = "1.0.9"

        buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
        buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
        buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
        // TMDB_API_KEY intentionally baked EMPTY: our /tmdb proxy is DB-served and ignores
        // api_key (auth is the user Bearer). No real key in the APK — rotatable server-side.
        buildConfigField("String", "TMDB_API_KEY", "\"\"")
        buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        if (enableDoviNative) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }
        buildConfigField("String", "DONATIONS_BASE_URL", "\"${localProperties.getProperty("DONATIONS_BASE_URL", "")}\"")
        buildConfigField("String", "DONATIONS_DONATE_URL", "\"${localProperties.getProperty("DONATIONS_DONATE_URL", "")}\"")
        buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
        buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
        buildConfigField("String", "CATALOG_ADDON_BASE_URL", "\"${localProperties.getProperty("CATALOG_ADDON_BASE_URL", "")}\"")
        // CATALOG_SECRET removed (F72): catalog-addon authenticates via the Supabase user
        // Bearer (see NetworkModule). No code usages; not baked into the APK.
        buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))

        // In-app updater (GitHub Releases)
        buildConfigField("String", "GITHUB_OWNER", "\"amrit-regmi\"")
        buildConfigField("String", "GITHUB_REPO", "\"NuvioTV\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            applicationId = "com.netflix.ninjax.tv"
            buildConfigField("boolean", "FEATURE_PLUGINS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_TRAILERS_ENABLED", "true")
            buildConfigField("String", "RECO_MODE", "\"private\"")
        }
        create("playstore") {
            dimension = "distribution"
            applicationId = "com.nuvio.app"
            buildConfigField("boolean", "FEATURE_PLUGINS_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_IN_APP_TRAILERS_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_EXTERNAL_TRAILERS_ENABLED", "true")
            buildConfigField("String", "RECO_MODE", "\"open\"")
        }
    }

    if (enableDoviNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = releaseKeyAliasValue
            keyPassword = releaseKeyPasswordValue
            storeFile = releaseStoreFilePath?.let(::file) ?: file("../nuviotv.jks")
            storePassword = releaseStorePasswordValue
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            // SECURITY: default is BLANK — a missing SYNC_BACKEND_MANIFEST_URL key must
            // yield NotConfigured (SyncBackendConfig/Repository) so default builds fetch
            // NOTHING from a third-party kill-switch host. Opt in explicitly via properties.
            buildConfigField("String", "SYNC_BACKEND_MANIFEST_URL", "\"${resolveProperty(devProperties, localProperties, "SYNC_BACKEND_MANIFEST_URL", "")}\"")

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "SUPABASE_URL", "\"${resolveProperty(devProperties, localProperties, "SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${resolveProperty(devProperties, localProperties, "SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "NUVIO_SUPABASE_URL", "\"${resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_URL")}\"")
            buildConfigField("String", "NUVIO_SUPABASE_ANON_KEY", "\"${resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "NUVIO_AVATAR_PUBLIC_BASE_URL", "\"${resolveProperty(devProperties, localProperties, "NUVIO_AVATAR_PUBLIC_BASE_URL")}\"")
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${devProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${devProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${devProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${devProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${devProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "DONATIONS_BASE_URL", "\"${devProperties.getProperty("DONATIONS_BASE_URL", localProperties.getProperty("DONATIONS_BASE_URL", ""))}\"")
            buildConfigField("String", "DONATIONS_DONATE_URL", "\"${devProperties.getProperty("DONATIONS_DONATE_URL", localProperties.getProperty("DONATIONS_DONATE_URL", ""))}\"")
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${devProperties.getProperty("AVATAR_PUBLIC_BASE_URL", localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", ""))}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${devProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", ""))}\"")
            buildConfigField("String", "CATALOG_ADDON_BASE_URL", "\"${devProperties.getProperty("CATALOG_ADDON_BASE_URL", localProperties.getProperty("CATALOG_ADDON_BASE_URL", ""))}\"")
            // CATALOG_SECRET removed (F72): catalog-addon uses Supabase user Bearer. Not baked.
            // F32: switch to hamrocinema.regmig.com at deploy — this is the SINGLE source of
            // truth for the reco/taste-engine host. All app code derives from BuildConfig.RECO_API_BASE_URL
            // (directly, or via com.nuvio.tv.core.reco.RecoBackend for host/catalog-addon matching).
            buildConfigField("String", "RECO_API_BASE_URL",
                "\"${localProperties.getProperty("RECO_API_BASE_URL", "https://hamrocinema.regmig.com")}\"")
            buildConfigField("String", "RECO_MODE",
                "\"${localProperties.getProperty("RECO_MODE", "private")}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        release {
            // R8 minification + resource shrinking ENABLED to produce a much smaller
            // installable APK for storage-constrained TV devices (Sony BRAVIA 4GB /data).
            // Conservative -keep rules in proguard-rules.pro protect reflective libs
            // (kotlinx.serialization, Gson, Moshi, Retrofit/OkHttp, Media3, DexClassLoader
            // extension deps, etc.) against over-shrinking. This is a real release variant:
            // isDebuggable=false (default) and applicationId has no ".debug" suffix.
            isMinifyEnabled = true
            isShrinkResources = true
            // Optimizing default proguard file restored. The launch ClassCastException
            // (typed DataStore Preferences read in PlayerSettingsDataStore) was NOT an
            // R8 optimizer bug — it was a settings-sync import writing an int-typed
            // player setting as a String into the shared "player_settings" store. The
            // durable fix is type-tolerant reads in PlayerSettingsDataStore (.safe()).
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
            // SECURITY: default is BLANK — see the debug variant note. Missing key must
            // never silently point releases at the third-party switch host.
            buildConfigField("String", "SYNC_BACKEND_MANIFEST_URL", "\"${localProperties.getProperty("SYNC_BACKEND_MANIFEST_URL", "")?.trim() ?: ""}\"")

            // Production environment (from local.properties)
            buildConfigField("String", "SUPABASE_URL", "\"${resolveLocalProperty(localProperties, "SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${resolveLocalProperty(localProperties, "SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "NUVIO_SUPABASE_URL", "\"${resolveLocalProperty(localProperties, "NUVIO_SUPABASE_URL")}\"")
            buildConfigField("String", "NUVIO_SUPABASE_ANON_KEY", "\"${resolveLocalProperty(localProperties, "NUVIO_SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "NUVIO_AVATAR_PUBLIC_BASE_URL", "\"${resolveLocalProperty(localProperties, "NUVIO_AVATAR_PUBLIC_BASE_URL")}\"")
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "DONATIONS_BASE_URL", "\"${localProperties.getProperty("DONATIONS_BASE_URL", "")}\"")
            buildConfigField("String", "DONATIONS_DONATE_URL", "\"${localProperties.getProperty("DONATIONS_DONATE_URL", "")}\"")
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
            // F32: switch to hamrocinema.regmig.com at deploy — this is the SINGLE source of
            // truth for the reco/taste-engine host. All app code derives from BuildConfig.RECO_API_BASE_URL
            // (directly, or via com.nuvio.tv.core.reco.RecoBackend for host/catalog-addon matching).
            buildConfigField("String", "RECO_API_BASE_URL",
                "\"${localProperties.getProperty("RECO_API_BASE_URL", "https://hamrocinema.regmig.com")}\"")
            buildConfigField("String", "RECO_MODE",
                "\"${localProperties.getProperty("RECO_MODE", "private")}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            applicationIdSuffix = ".debug"
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = !buildingAppBundle
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    androidResources {
        // English-only resources: drops the 30+ translated locale variants from
        // the APK (the app UI is effectively English-only for this fork).
        localeFilters += listOf("en")
    }

    lint {
        // The audit batch deletes dead strings from the BASE values/strings.xml
        // only; the ~30 locale copies are intentionally left untouched (and are
        // excluded from the APK by the en-only localeFilters above). A translation
        // without a default-locale entry trips the fatal ExtraTranslation check in
        // lintVitalRelease, so disable that check explicitly.
        disable += "ExtraTranslation"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so",
                "lib/*/libtorrserver.so"
            )
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val isPlaystore = variant.productFlavors.any { it.second == "playstore" }
        val isFull = variant.productFlavors.any { it.second == "full" }
        variant.applicationId.set(when {
            isPlaystore -> "com.nuvio.appdebug"
            isFull -> "com.netflix.ninjax.tv.debug"
            else -> "com.nuviodebug.com"
        })
    }
}

composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// Globally exclude stock media3-exoplayer and media3-ui — replaced by the
// prebuilt forked AARs (lib-exoplayer-release.aar / lib-ui-release.aar).
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "generated/baselineProfiles"
    filter {
        include("com.nuvio.tv.**")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")

    // Source-retention nullness annotations (MonotonicNonNull / RequiresNonNull /
    // EnsuresNonNull) used by the vendored Matroska extractor in
    // com.nuvio.tv.core.player.dvmkv. Media3 keeps these compileOnly in its own
    // build, so they aren't on our classpath via the prebuilt AARs.
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-compose:1.11.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 core modules. media3-exoplayer and media3-ui are globally excluded
    // (above) and replaced by the prebuilt forked AARs below; everything else is
    // stock Maven.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.media3.extractor)

    // REQUIRED at runtime by the custom media3-ui prebuilt (PlayerView uses RecyclerView); local AARs carry no transitive deps — do not remove as "unused"
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Local AAR libraries from forked ExoPlayer (matching Just Player setup):
    // - lib-exoplayer-release.aar    — Custom forked ExoPlayer core (replaces media3-exoplayer)
    // - lib-ui-release.aar           — Custom forked ExoPlayer UI
    // - lib-decoder-av1-release.aar  — AV1 software video decoder (libgav1)
    // - lib-decoder-iamf-release.aar — IAMF immersive audio decoder
    // - lib-decoder-mpegh-release.aar — MPEG-H 3D audio decoder
    implementation(files(
        "libs/lib-exoplayer-release.aar",
        "libs/lib-ui-release.aar",
        "libs/lib-decoder-av1-release.aar",
        "libs/lib-decoder-iamf-release.aar",
        "libs/lib-decoder-mpegh-release.aar"
    ))
    if (useLocalFfmpegDecoder) {
        implementation(project(":ffmpeg-decoder-downmix"))
    } else {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    // libass-android for ASS/SSA subtitle support (from Maven Central)
    implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
    // Local nextlib-mediainfo fork (static FFmpeg; no libav*.so in final AAR)
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("dev.chrisbanes.haze:haze-android:0.7.3") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }

    implementation(libs.gson)

    add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    add("fullImplementation", libs.jsoup)
    add("fullImplementation", "com.fasterxml.jackson.core:jackson-databind:2.17.0")
    add("fullImplementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    add("fullImplementation", libs.nicehttp)
    add("fullImplementation", libs.conscrypt.android)
    add("fullImplementation", "com.github.recloudstream.cloudstream:library:${libs.versions.cloudstream.get()}") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "com.github.AmarullisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX.newpipeextractor")
        exclude(group = "info.debatty", module = "java-string-similarity")
    }

    // Markdown rendering — used only by the full-flavor in-app updater UI
    // (UpdatePromptDialog), so keep it out of the playstore flavor.
    add("fullImplementation", libs.markdown.renderer.m3)

    add("fullImplementation", libs.crypto.js)
    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)


    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Performance profiling
    implementation("androidx.metrics:metrics-performance:1.0.0-rc01")  // JankStats
    debugImplementation("androidx.compose.runtime:runtime-tracing")

    add("fullImplementation", "org.webjars.npm:crypto-js:4.2.0")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
