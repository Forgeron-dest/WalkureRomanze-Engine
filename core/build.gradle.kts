plugins {
    kotlin("jvm")
}

group = "org.walkure"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

val gdxVersion = "1.14.2"
val ktxVersion = "1.13.1-rc1"

dependencies {
    // LibGDX Core, FreeType & LibKTX (Platform Independent)
    api("com.badlogicgames.gdx:gdx:$gdxVersion")
    api("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

    api("io.github.libktx:ktx-app:$ktxVersion")
    api("io.github.libktx:ktx-graphics:$ktxVersion")
    api("io.github.libktx:ktx-freetype:$ktxVersion")

    // Tests run on desktop JVM
    testImplementation(kotlin("test"))
    testImplementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    testImplementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
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

tasks.test {
    useJUnitPlatform()
    jvmArgs(modernJvmNativeArgs)
}
