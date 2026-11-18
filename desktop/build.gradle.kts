import org.gradle.jvm.tasks.Jar
import java.io.File

plugins {
    kotlin("jvm")
    application
}

group = "org.walkure"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

val gdxVersion = "1.14.2"

dependencies {
    implementation(project(":core"))

    // LibGDX Desktop Backend (LWJGL3) & Desktop Natives
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
}

kotlin {
    jvmToolchain(25)
}

val modernJvmNativeArgs = listOf(
    "-Xms256m",
    "-Xmx2048m",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--sun-misc-unsafe-memory-access=allow"
)

application {
    mainClass.set("walkure.MainKt")
    applicationDefaultJvmArgs = modernJvmNativeArgs
}

// --- Multi-Platform Desktop Packaging Tasks (Fat JAR, Linux Runnable Binary, Windows Launcher) ---

val packageFatJar = tasks.register<Jar>("packageFatJar") {
    group = "distribution"
    description = "Builds a standalone cross-platform fat JAR containing Kotlin, LibGDX, LWJGL3, and KTX."
    archiveFileName.set("walkure-romanze-desktop.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "walkure.MainKt"
    }

    from(sourceSets.main.get().output)
    from(project(":core").sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

val packageLinux = tasks.register("packageLinux") {
    group = "distribution"
    description = "Packages a self-executing Linux binary (walkure-romanze-linux) and launcher script."
    dependsOn(packageFatJar)

    doLast {
        val distDir = rootProject.layout.buildDirectory.dir("dist").get().asFile
        distDir.mkdirs()
        val fatJar = File(distDir, "walkure-romanze-desktop.jar")
        val jvmFlags = modernJvmNativeArgs.joinToString(" ")

        val linuxBinary = File(distDir, "walkure-romanze-linux")
        val stub = """
            #!/usr/bin/env sh
            MYSELF=`which "${'$'}0" 2>/dev/null`
            [ ${'$'}? -gt 0 -a -f "${'$'}0" ] && MYSELF="./${'$'}0"
            JAVA_BIN=java
            if [ -n "${'$'}JAVA_HOME" ]; then
                JAVA_BIN="${'$'}JAVA_HOME/bin/java"
            fi
            if [ "${'$'}#" -eq 0 ]; then
                exec "${'$'}JAVA_BIN" $jvmFlags -jar "${'$'}MYSELF" play --honorifics HON
            else
                exec "${'$'}JAVA_BIN" $jvmFlags -jar "${'$'}MYSELF" "${'$'}@"
            fi
            exit 1
        """.trimIndent() + "\n"

        linuxBinary.outputStream().use { out ->
            out.write(stub.toByteArray(Charsets.UTF_8))
            fatJar.inputStream().use { it.copyTo(out) }
        }
        linuxBinary.setExecutable(true)

        val linuxScript = File(distDir, "walkure-linux.sh")
        val scriptContent = """
            #!/usr/bin/env sh
            DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)"
            JAVA_BIN=java
            if [ -n "${'$'}JAVA_HOME" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
                JAVA_BIN="${'$'}JAVA_HOME/bin/java"
            elif [ -x "${'$'}HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/java" ]; then
                JAVA_BIN="${'$'}HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/java"
            fi
            JAR_PATH="${'$'}DIR/walkure-romanze-desktop.jar"
            [ ! -f "${'$'}JAR_PATH" ] && JAR_PATH="${'$'}DIR/walkure-kotlin/build/dist/walkure-romanze-desktop.jar"

            case "${'$'}1" in
                verify|list|extract|inspect-script|export-keys)
                    exec "${'$'}JAVA_BIN" $jvmFlags -jar "${'$'}JAR_PATH" "${'$'}@"
                    ;;
                play)
                    shift
                    exec "${'$'}JAVA_BIN" $jvmFlags -jar "${'$'}JAR_PATH" play --honorifics HON "${'$'}@"
                    ;;
                *)
                    exec "${'$'}JAVA_BIN" $jvmFlags -jar "${'$'}JAR_PATH" play --honorifics HON "${'$'}@"
                    ;;
            esac
        """.trimIndent() + "\n"
        linuxScript.writeText(scriptContent)
        linuxScript.setExecutable(true)
        println("Created Linux self-executing binary: ${linuxBinary.absolutePath} (${linuxBinary.length()} bytes)")
    }
}

val packageWindows = tasks.register("packageWindows") {
    group = "distribution"
    description = "Packages Windows launcher (.bat) and jpackage configuration."
    dependsOn(packageFatJar)

    doLast {
        val distDir = rootProject.layout.buildDirectory.dir("dist").get().asFile
        distDir.mkdirs()
        val jvmFlags = modernJvmNativeArgs.joinToString(" ")
        val fatJar = File(distDir, "walkure-romanze-desktop.jar")
        val jpackageInputDir = File(distDir, "jpackage-input")
        jpackageInputDir.mkdirs()
        fatJar.copyTo(File(jpackageInputDir, fatJar.name), overwrite = true)

        val batFile = File(distDir, "walkure-windows.bat")
        batFile.writeText(
            """
            @echo off
            setlocal
            set DIR=%~dp0
            java $jvmFlags -jar "%DIR%walkure-romanze-desktop.jar" play --honorifics HON %*
            """.trimIndent() + "\r\n"
        )

        val jpackageArgs = File(distDir, "walkure-windows-jpackage.args")
        jpackageArgs.writeText(
            """
            --type app-image
            --name WalkureRomanze
            --input jpackage-input
            --main-jar walkure-romanze-desktop.jar
            --main-class walkure.MainKt
            --java-options -Xmx2048m
            --java-options --enable-native-access=ALL-UNNAMED
            --java-options -XX:+IgnoreUnrecognizedVMOptions
            --java-options --sun-misc-unsafe-memory-access=allow
            --arguments "play"
            --arguments "--honorifics"
            --arguments "HON"
            """.trimIndent() + "\n"
        )
        println("Created Windows launchers in: ${distDir.absolutePath}")
    }
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Builds all desktop distribution bundles (Fat JAR, Linux runnable, Windows package)."
    dependsOn(packageFatJar, packageLinux, packageWindows)
}
