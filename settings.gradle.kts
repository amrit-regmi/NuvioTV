pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
    // AGP Settings plugin: lets us run R8 in a SEPARATE forked JVM with its own
    // heap (configured below), so R8 minification of this large app no longer OOMs
    // inside the Gradle daemon — WITHOUT touching org.gradle.jvmargs (owner locks
    // the daemon at -Xmx1g). See android.execution block below.
    id("com.android.settings") version "8.13.2"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Run R8 (release minification) in its own forked process with a large heap. The
// Gradle daemon stays at org.gradle.jvmargs=-Xmx1g; R8 gets its own -Xmx6g here.
// The box has ample RAM + swap, so the forked R8 JVM completes the shrink without
// the OutOfMemoryError that occurs when R8 runs in-daemon (noIsolation) mode.
android {
    execution {
        profiles {
            create("highmem") {
                r8 {
                    runInSeparateProcess = true
                    jvmOptions += listOf(
                        "-Xmx6g",
                        "-XX:MaxMetaspaceSize=1g",
                        "-XX:+UseParallelGC",
                        "-Dfile.encoding=UTF-8"
                    )
                }
            }
        }
        defaultProfile = "highmem"
    }
}

rootProject.name = "My Application"
include(":app")
include(":baselineprofile")
include(":ffmpeg-decoder-downmix")
