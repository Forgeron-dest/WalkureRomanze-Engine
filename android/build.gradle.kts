import java.io.File

plugins {
    id("com.android.application")
}

val natives = configurations.create("natives")

android {
    namespace = "org.walkure.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.walkure.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.version.toString()

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    sourceSets {
        named("main") {
            jniLibs.directories.add("libs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes.add("META-INF/INDEX.LIST")
            excludes.add("META-INF/DEPENDENCIES")
        }
    }
}

val gdxVersion = "1.14.2"

dependencies {
    implementation(project(":core"))

    // LibGDX Android Backend & FreeType
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

    // Native binaries for all Android architectures
    add(natives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    add(natives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    add(natives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
    add(natives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")

    add(natives.name, "com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a")
    add(natives.name, "com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-armeabi-v7a")
    add(natives.name, "com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-x86_64")
    add(natives.name, "com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-x86")
}

// Unpack LibGDX native shared libraries (.so) into libs/
val copyAndroidNatives = tasks.register("copyAndroidNatives") {
    doFirst {
        file("libs/arm64-v8a").mkdirs()
        file("libs/armeabi-v7a").mkdirs()
        file("libs/x86_64").mkdirs()
        file("libs/x86").mkdirs()

        natives.files.forEach { jar ->
            val outputDir = when {
                jar.name.contains("natives-arm64-v8a") -> file("libs/arm64-v8a")
                jar.name.contains("natives-armeabi-v7a") -> file("libs/armeabi-v7a")
                jar.name.contains("natives-x86_64") -> file("libs/x86_64")
                jar.name.contains("natives-x86") -> file("libs/x86")
                else -> null
            }
            if (outputDir != null) {
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
        println("[APK Build] Extracted native libraries for arm64-v8a, armeabi-v7a, x86_64, x86")
    }
}

// Automatically bundle walkure.keys and WalkureCustomV4.ttf into APK assets if found in project
val copyAssets = tasks.register("copyAssets") {
    doLast {
        val assetsDir = file("src/main/assets")
        assetsDir.mkdirs()

        // 1. Portable 512-byte decryption keys
        val keysCandidates = listOf(
            file("../GameData/walkure.keys"),
            file("../../GameData/walkure.keys"),
            file("../walkure.keys"),
            file("../../walkure.keys")
        )
        keysCandidates.firstOrNull { it.exists() && it.length() >= 512L }?.let { keysFile ->
            val dest = File(assetsDir, "walkure.keys")
            keysFile.copyTo(dest, overwrite = true)
            println("[APK Build] Bundled portable decryption keys (${dest.length()} bytes) into assets/walkure.keys")
        }

        // 2. Custom game TTF font
        val fontCandidates = listOf(
            file("../WalkureCustomV4.ttf"),
            file("../../WalkureCustomV4.ttf")
        )
        fontCandidates.firstOrNull { it.exists() }?.let { fontFile ->
            val dest = File(assetsDir, "WalkureCustomV4.ttf")
            fontFile.copyTo(dest, overwrite = true)
            println("[APK Build] Bundled TTF font (${dest.length()} bytes) into assets/WalkureCustomV4.ttf")
        }

        // 3. Bundled startup logo video (ricotta_logo.mp4)
        val logoDest = File(assetsDir, "ricotta_logo.mp4")
        if (!logoDest.exists() || logoDest.length() == 0L) {
            val logoCandidates = listOf(
                file("../ricotta_logo.mp4"),
                file("../../ricotta_logo.mp4"),
                file("../GameData/movie/ricotta_logo.mp4"),
                file("../../GameData/movie/ricotta_logo.mp4")
            )
            logoCandidates.firstOrNull { it.exists() && it.length() > 0L }?.let { logoFile ->
                logoFile.copyTo(logoDest, overwrite = true)
                println("[APK Build] Bundled startup logo video (${logoDest.length()} bytes) into assets/ricotta_logo.mp4")
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(copyAndroidNatives, copyAssets)
}
