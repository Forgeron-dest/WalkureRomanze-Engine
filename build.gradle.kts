import java.io.File
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.diffplug.spotless") version "8.11.0"
    kotlin("jvm") version "2.4.21" apply false
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt().googleStyle()
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the Walkure Romanze desktop game"
    val desktop = project(":desktop")
    classpath = desktop.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("walkure.MainKt")
    providers.gradleProperty("walkureGameDir").orNull?.let { workingDir = file(it) }
    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx2048m",
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

tasks.register("test") {
    group = "verification"
    description = "Runs the engine verification test suite"
    dependsOn(":core:test")
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Packages all desktop bundles (fat JAR, Linux runnable, Windows package)"
    dependsOn(":desktop:packageAll")
}

tasks.register("buildApk") {
    group = "distribution"
    description = "Builds the Android APK (walkure-romanze.apk) in build/dist/"
    dependsOn(":android:assembleDebug")
    doLast {
        val distDir = layout.buildDirectory.dir("dist").get().asFile
        distDir.mkdirs()
        val apk = file("android/build/outputs/apk/debug/android-debug.apk")
        if (apk.exists()) {
            val dest = File(distDir, "walkure-romanze.apk")
            apk.copyTo(dest, overwrite = true)
            println("=== Android APK Built Successfully ===")
            println("Output: ${dest.absolutePath} (${dest.length()} bytes)")
        }
    }
}

tasks.register("restoreLauncherIcons") {
    group = "distribution"
    description = "Creates Android launcher icon sizes from a local game portrait"
    doLast {
        val sourcePath = providers.gradleProperty("launcherIconSource").orNull
            ?: throw GradleException("Set -PlauncherIconSource to an extracted game portrait PNG.")
        val source = ImageIO.read(file(sourcePath))
            ?: throw GradleException("Could not read launcher icon source: $sourcePath")
        val cropHeight = (source.height * 0.66).toInt().coerceIn(1, source.height)
        val iconSizes = listOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192
        )

        for ((density, size) in iconSizes) {
            val outputDir = file("android/src/main/res/mipmap-$density")
            outputDir.mkdirs()
            val icon = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = icon.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(source, 0, 0, size, size, 0, 0, source.width, cropHeight, null)
            } finally {
                graphics.dispose()
            }

            for (name in listOf("ic_launcher.png", "ic_launcher_round.png")) {
                check(ImageIO.write(icon, "png", File(outputDir, name))) {
                    "No PNG writer is available for ${File(outputDir, name)}"
                }
            }
        }
        println("[Launcher Icons] Restored Mio character icons from the local game archive.")
    }
}
